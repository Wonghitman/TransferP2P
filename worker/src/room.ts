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

interface TransferInvite {
  inviteId: string;
  code: string;
  wsUrl: string;
  fromDeviceId: string;
  fromDisplayName: string;
  toDeviceId: string;
  expiresAt: number;
}

const PAIRING_PREFIX = "pairing:";
const TRUSTED_PREFIX = "trusted:";
const INVITE_PREFIX = "invite:";
const PROFILE_PREFIX = "profile:";

interface DeviceProfile {
  deviceId: string;
  publicKey: string;
  displayName: string;
}

export class RoomDurableObject implements DurableObject {
  private peers = new Map<string, PeerSession>();
  private presenceSockets = new Map<string, WebSocket>();
  private roomCode = "";
  private expiresAt = 0;

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
      const pairingCode = body.pairingCode.trim().toUpperCase();
      const registration: DeviceRegistration = {
        ...body,
        pairingCode,
        expiresAt: body.expiresAt,
      };
      await this.state.storage.put(`${PAIRING_PREFIX}${pairingCode}`, registration);
      await this.saveProfile(body.deviceId, body.publicKey, body.displayName);
      return new Response("ok");
    }

    if (url.pathname === "/device-claim" && request.method === "POST") {
      const body = await request.json<{
        pairingCode: string;
        deviceId: string;
        publicKey: string;
        displayName: string;
      }>();
      const pairingCode = body.pairingCode.trim().toUpperCase();
      if (pairingCode.length < 6) {
        return new Response("invalid length", { status: 400 });
      }
      const registration = await this.state.storage.get<DeviceRegistration>(
        `${PAIRING_PREFIX}${pairingCode}`,
      );
      if (!registration) {
        return new Response("not found", { status: 400 });
      }
      if (registration.expiresAt < Date.now()) {
        return new Response("expired", { status: 400 });
      }
      if (registration.deviceId === body.deviceId) {
        return new Response("self claim", { status: 400 });
      }
      await this.saveProfile(registration.deviceId, registration.publicKey, registration.displayName);
      await this.saveProfile(body.deviceId, body.publicKey, body.displayName);
      await this.addTrustedRelation(registration.deviceId, body.deviceId);
      await this.state.storage.delete(`${PAIRING_PREFIX}${pairingCode}`);
      return Response.json({
        deviceId: registration.deviceId,
        publicKey: registration.publicKey,
        displayName: registration.displayName,
      });
    }

    const trustedMatch = url.pathname.match(/^\/device-trusted\/(.+)$/);
    if (trustedMatch) {
      const deviceId = trustedMatch[1];
      const ids = await this.getTrustedIds(deviceId);
      const result = await Promise.all(
        ids.map(async (id) => {
          const profile = await this.getProfile(id);
          return {
            deviceId: id,
            publicKey: profile?.publicKey ?? "",
            displayName: profile?.displayName ?? id,
            online: this.presenceSockets.has(id),
          };
        }),
      );
      return Response.json(result);
    }

    if (url.pathname === "/device-online-status" && request.method === "POST") {
      const body = await request.json<{ deviceIds: string[] }>();
      const online: Record<string, boolean> = {};
      for (const id of body.deviceIds ?? []) {
        online[id] = this.presenceSockets.has(id);
      }
      return Response.json({ online });
    }

    if (url.pathname === "/device-trust-check" && request.method === "GET") {
      const fromDeviceId = url.searchParams.get("from") ?? "";
      const toDeviceId = url.searchParams.get("to") ?? "";
      const trusted = await this.getTrustedIds(fromDeviceId);
      if (!trusted.includes(toDeviceId)) {
        return new Response("not trusted", { status: 403 });
      }
      return new Response("ok");
    }

    if (url.pathname === "/device-invite" && request.method === "POST") {
      const body = await request.json<TransferInvite>();
      await this.state.storage.put(`${INVITE_PREFIX}${body.toDeviceId}`, body);
      this.pushInvite(body.toDeviceId, body);
      return new Response("ok");
    }

    const invitesMatch = url.pathname.match(/^\/device-invites\/(.+)$/);
    if (invitesMatch && request.method === "GET") {
      const deviceId = invitesMatch[1];
      const invite = await this.state.storage.get<TransferInvite>(`${INVITE_PREFIX}${deviceId}`);
      if (!invite || invite.expiresAt < Date.now()) {
        if (invite) {
          await this.state.storage.delete(`${INVITE_PREFIX}${deviceId}`);
        }
        return Response.json(null);
      }
      return Response.json(invite);
    }

    if (url.pathname === "/device-invites-consume" && request.method === "POST") {
      const body = await request.json<{ toDeviceId: string }>();
      await this.state.storage.delete(`${INVITE_PREFIX}${body.toDeviceId}`);
      return new Response("ok");
    }

    if (url.pathname === "/device-presence-ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected websocket", { status: 426 });
      }
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      this.handlePresenceWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
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

  private handlePresenceWebSocket(socket: WebSocket) {
    socket.accept();
    let deviceId = "";

    socket.addEventListener("message", (event) => {
      try {
        const message = JSON.parse(String(event.data));
        if (message.type === "presence" && typeof message.deviceId === "string") {
          deviceId = message.deviceId;
          this.presenceSockets.set(deviceId, socket);
          socket.send(JSON.stringify({ type: "presence_ack", deviceId, online: true }));
          this.broadcastPresence(deviceId, true);
        }
      } catch {
        // ignore malformed messages
      }
    });

    socket.addEventListener("close", () => {
      if (deviceId) {
        const current = this.presenceSockets.get(deviceId);
        if (current === socket) {
          this.presenceSockets.delete(deviceId);
          this.broadcastPresence(deviceId, false);
        }
      }
    });
  }

  private pushInvite(deviceId: string, invite: TransferInvite) {
    const socket = this.presenceSockets.get(deviceId);
    if (!socket) return;
    socket.send(
      JSON.stringify({
        type: "transfer_invite",
        inviteId: invite.inviteId,
        code: invite.code,
        wsUrl: invite.wsUrl,
        fromDeviceId: invite.fromDeviceId,
        fromDisplayName: invite.fromDisplayName,
        expiresAt: invite.expiresAt,
      }),
    );
  }

  private broadcastPresence(deviceId: string, online: boolean) {
    const payload = JSON.stringify({ type: "presence_update", deviceId, online });
    for (const [id, socket] of this.presenceSockets) {
      if (id !== deviceId) {
        socket.send(payload);
      }
    }
  }

  private async saveProfile(deviceId: string, publicKey: string, displayName: string) {
    const profile: DeviceProfile = { deviceId, publicKey, displayName };
    await this.state.storage.put(`${PROFILE_PREFIX}${deviceId}`, profile);
  }

  private async getProfile(deviceId: string): Promise<DeviceProfile | null> {
    return (await this.state.storage.get<DeviceProfile>(`${PROFILE_PREFIX}${deviceId}`)) ?? null;
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
    if (this.roomCode) {
      const keys = await this.state.storage.list();
      for (const key of keys.keys) {
        if (!key.startsWith(PAIRING_PREFIX) &&
            !key.startsWith(TRUSTED_PREFIX) &&
            !key.startsWith(PROFILE_PREFIX) &&
            !key.startsWith(INVITE_PREFIX)) {
          await this.state.storage.delete(key);
        }
      }
      this.roomCode = "";
      this.expiresAt = 0;
    }
  }

  private async getTrustedIds(deviceId: string): Promise<string[]> {
    return (await this.state.storage.get<string[]>(`${TRUSTED_PREFIX}${deviceId}`)) ?? [];
  }

  private async addTrustedRelation(ownerId: string, peerId: string): Promise<void> {
    const ownerPeers = new Set(await this.getTrustedIds(ownerId));
    ownerPeers.add(peerId);
    await this.state.storage.put(`${TRUSTED_PREFIX}${ownerId}`, Array.from(ownerPeers));

    const peerOwners = new Set(await this.getTrustedIds(peerId));
    peerOwners.add(ownerId);
    await this.state.storage.put(`${TRUSTED_PREFIX}${peerId}`, Array.from(peerOwners));
  }
}
