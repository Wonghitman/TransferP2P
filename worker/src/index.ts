import { RoomDurableObject } from "./room";

export { RoomDurableObject };

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    try {
      if (url.pathname === "/rooms" && request.method === "POST") {
        return withCors(await createRoom(request, env));
      }

      const roomMatch = url.pathname.match(/^\/rooms\/([^/]+)$/);
      if (roomMatch && request.method === "GET") {
        return withCors(await joinRoom(roomMatch[1], env));
      }

      const wsMatch = url.pathname.match(/^\/rooms\/([^/]+)\/ws$/);
      if (wsMatch && request.headers.get("Upgrade") === "websocket") {
        return await connectRoomWebSocket(wsMatch[1], request, env);
      }

      if (url.pathname === "/turn-credentials" && request.method === "POST") {
        return withCors(await issueTurnCredentials(env));
      }

      if (url.pathname === "/devices/register" && request.method === "POST") {
        return withCors(await registerDevice(request, env));
      }

      if (url.pathname === "/devices/claim" && request.method === "POST") {
        return withCors(await claimDevice(request, env));
      }

      const trustedMatch = url.pathname.match(/^\/devices\/([^/]+)\/trusted$/);
      if (trustedMatch && request.method === "GET") {
        return withCors(await listTrustedDevices(trustedMatch[1], env));
      }

      return withCors(json({ error: "Not found" }, 404));
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown error";
      return withCors(json({ error: message }, 500));
    }
  },
};

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  Object.entries(CORS_HEADERS).forEach(([key, value]) => headers.set(key, value));
  return new Response(response.body, {
    status: response.status,
    headers,
  });
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function createRoom(_request: Request, env: Env): Promise<Response> {
  const code = generateRoomCode();
  const id = env.ROOM.idFromName(code);
  const stub = env.ROOM.get(id);
  const ttl = Number(env.ROOM_TTL_SECONDS || "3600");
  const expiresAt = Date.now() + ttl * 1000;
  await stub.fetch("https://room/init", {
    method: "POST",
    body: JSON.stringify({ code, expiresAt }),
  });
  const base = env.PUBLIC_BASE_URL.replace(/\/$/, "");
  return json({
    code,
    joinUrl: `${base}/join/${code}`,
    wsUrl: `${base}/rooms/${code}/ws`,
    expiresAt,
  });
}

async function joinRoom(code: string, env: Env): Promise<Response> {
  const id = env.ROOM.idFromName(code);
  const stub = env.ROOM.get(id);
  const response = await stub.fetch("https://room/status");
  if (!response.ok) {
    return json({ error: "Room not found" }, 404);
  }
  const status = await response.json<{ expiresAt: number }>();
  const base = env.PUBLIC_BASE_URL.replace(/\/$/, "");
  return json({
    code,
    wsUrl: `${base}/rooms/${code}/ws`,
    expiresAt: status.expiresAt,
  });
}

async function connectRoomWebSocket(
  code: string,
  request: Request,
  env: Env,
): Promise<Response> {
  const id = env.ROOM.idFromName(code);
  const stub = env.ROOM.get(id);
  return stub.fetch(request);
}

async function issueTurnCredentials(env: Env): Promise<Response> {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) {
    return json({
      iceServers: [
        { urls: ["stun:stun.cloudflare.com:3478"] },
      ],
      ttl: 3600,
    });
  }

  const ttl = 3600;
  const response = await fetch(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ ttl }),
    },
  );

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`TURN credential request failed: ${text}`);
  }

  const data = await response.json<{
    iceServers: { urls: string | string[]; username?: string; credential?: string }[];
  }>();

  const iceServers = data.iceServers.map((server) => ({
    urls: Array.isArray(server.urls) ? server.urls : [server.urls],
    username: server.username,
    credential: server.credential,
  }));

  return json({ iceServers, ttl });
}

async function registerDevice(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{
    deviceId: string;
    publicKey: string;
    displayName: string;
  }>();
  const pairingCode = generatePairingCode();
  const expiresAt = Date.now() + 10 * 60 * 1000;
  const id = env.ROOM.idFromName(`device-registry`);
  const stub = env.ROOM.get(id);
  await stub.fetch("https://room/device-register", {
    method: "POST",
    body: JSON.stringify({ ...body, pairingCode, expiresAt }),
  });
  return json({ deviceId: body.deviceId, pairingCode, expiresAt });
}

async function claimDevice(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{
    pairingCode: string;
    deviceId: string;
    publicKey: string;
    displayName: string;
  }>();
  const id = env.ROOM.idFromName(`device-registry`);
  const stub = env.ROOM.get(id);
  const response = await stub.fetch("https://room/device-claim", {
    method: "POST",
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    return json({ error: "Invalid pairing code" }, 400);
  }
  return json(await response.json());
}

async function listTrustedDevices(deviceId: string, env: Env): Promise<Response> {
  const id = env.ROOM.idFromName(`device-registry`);
  const stub = env.ROOM.get(id);
  const response = await stub.fetch(`https://room/device-trusted/${deviceId}`);
  if (!response.ok) {
    return json([]);
  }
  return json(await response.json());
}

function generateRoomCode(): string {
  const words = ["apple", "blue", "cloud", "delta", "echo", "flame", "green", "haze"];
  return Array.from({ length: 4 }, () => words[Math.floor(Math.random() * words.length)]).join("-");
}

function generatePairingCode(): string {
  return Math.random().toString(36).slice(2, 8).toUpperCase();
}
