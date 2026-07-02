interface PeerSession {
  peerId: string;
  socket: WebSocket;
}

interface DeviceRegistration {
  deviceId: string;
  publicKey: string;
  displayName: string;
  pairingCode: string;
  expiresAt: number;
}

export class RoomDurableObject implements DurableObject {
  private peers = new Map<string, PeerSession>();
  private roomCode = "";
  private expiresAt = 0;
  private registrations = new Map<string, DeviceRegistration>();
  private trusted = new Map<string, Set<string>>();

  constructor(private state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/init" && request.method === "POST") {
      const body = await request.json<{ code: string; expiresAt: number }>();
      this.roomCode = body.code;
      this.expiresAt = body.expiresAt;
      await this.state.storage.put("expiresAt", body.expiresAt);
      const alarmAt = body.expiresAt;
      await this.state.storage.setAlarm(alarmAt);
      return new Response("ok");
    }

    if (url.pathname === "/status") {
      const expiresAt =
        this.expiresAt || (await this.state.storage.get<number>("expiresAt")) || 0;
      if (!expiresAt || Date.now() > expiresAt) {
        return new Response("expired", { status: 410 });
      }
      return Response.json({ code: this.roomCode, expiresAt });
    }

    if (url.pathname === "/device-register" && request.method === "POST") {
      const body = await request.json<DeviceRegistration>();
      this.registrations.set(body.pairingCode, body);
      return new Response("ok");
    }

    if (url.pathname === "/device-claim" && request.method === "POST") {
      const body = await request.json<{
        pairingCode: string;
        deviceId: string;
        publicKey: string;
        displayName: string;
      }>();
      const registration = this.registrations.get(body.pairingCode);
      if (!registration || registration.expiresAt < Date.now()) {
        return new Response("invalid", { status: 400 });
      }
      const ownerDevices = this.trusted.get(registration.deviceId) ?? new Set();
      ownerDevices.add(body.deviceId);
      this.trusted.set(registration.deviceId, ownerDevices);
      const claimerDevices = this.trusted.get(body.deviceId) ?? new Set();
      claimerDevices.add(registration.deviceId);
      this.trusted.set(body.deviceId, claimerDevices);
      this.registrations.delete(body.pairingCode);
      return Response.json({
        deviceId: registration.deviceId,
        publicKey: registration.publicKey,
        displayName: registration.displayName,
      });
    }

    const trustedMatch = url.pathname.match(/^\/device-trusted\/(.+)$/);
    if (trustedMatch) {
      const deviceId = trustedMatch[1];
      const ids = Array.from(this.trusted.get(deviceId) ?? []);
      const result = ids.map((id) => ({
        deviceId: id,
        publicKey: "",
        displayName: id,
      }));
      return Response.json(result);
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    await this.handleWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  private async handleWebSocket(socket: WebSocket) {
    socket.accept();

  socket.addEventListener("message", async (event) => {
      try {
        const message = JSON.parse(String(event.data));
        if (message.type === "join") {
          const peerId = message.peerId as string;
          this.peers.set(peerId, { peerId, socket });
          const peers = Array.from(this.peers.keys());
          socket.send(JSON.stringify({ type: "joined", peerId, peers }));
          for (const [id, peer] of this.peers) {
            if (id !== peerId) {
              peer.socket.send(JSON.stringify({ type: "joined", peerId, peers }));
            }
          }
          return;
        }

        const from = message.from ?? message.peerId;
        if (!from) return;

        for (const [peerId, peer] of this.peers) {
          if (peerId !== from) {
            peer.socket.send(String(event.data));
          }
        }
      } catch {
        // ignore malformed messages
      }
    });

    socket.addEventListener("close", () => {
      for (const [peerId, peer] of this.peers) {
        if (peer.socket === socket) {
          this.peers.delete(peerId);
          const leave = JSON.stringify({ type: "leave", peerId });
          for (const [, other] of this.peers) {
            other.socket.send(leave);
          }
          break;
        }
      }
    });
  }

  async alarm(): Promise<void> {
    this.peers.forEach((peer) => peer.socket.close(1000, "room expired"));
    this.peers.clear();
    await this.state.storage.deleteAll();
  }
}
