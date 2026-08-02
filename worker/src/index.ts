import { RoomDurableObject } from "./room";
import { ROOM_WORDS } from "./room-words";

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
      if (url.pathname === "/" && request.method === "GET") {
        return withCors(json({ ok: true, service: "sendmaster", mode: "stun-only" }));
      }

      if (url.pathname === "/debug" && request.method === "GET") {
        return withCors(debugPage());
      }

      const joinMatch = url.pathname.match(/^\/join\/([^/]+)$/);
      if (joinMatch && request.method === "GET") {
        return withCors(joinLandingPage(joinMatch[1], env));
      }

      if (url.pathname === "/rooms" && request.method === "POST") {
        return withCors(await createRoom(request, env));
      }

      if (url.pathname === "/rooms" && request.method === "GET") {
        return withCors(
          json(
            {
              error: "Use POST /rooms to create a room",
              hint: "GET / for health check",
            },
            405,
          ),
        );
      }

      const roomMatch = url.pathname.match(/^\/rooms\/([^/]+)$/);
      if (roomMatch && request.method === "GET") {
        return withCors(await joinRoom(roomMatch[1], env));
      }

      const wsMatch = url.pathname.match(/^\/rooms\/([^/]+)\/ws$/);
      if (wsMatch && request.headers.get("Upgrade") === "websocket") {
        return await connectRoomWebSocket(wsMatch[1], request, env);
      }

      if (url.pathname === "/ice-servers" && request.method === "POST") {
        return withCors(await issueIceServers());
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

      if (url.pathname === "/devices/invite" && request.method === "POST") {
        return withCors(await createDeviceInvite(request, env));
      }

      const invitesMatch = url.pathname.match(/^\/devices\/([^/]+)\/invites$/);
      if (invitesMatch && request.method === "GET") {
        return withCors(await getPendingInvite(invitesMatch[1], env));
      }

      if (url.pathname === "/devices/invites/consume" && request.method === "POST") {
        return withCors(await consumeDeviceInvite(request, env));
      }

      if (url.pathname === "/devices/online-status" && request.method === "POST") {
        return withCors(await fetchOnlineStatus(request, env));
      }

      if (url.pathname === "/devices/ws" && request.headers.get("Upgrade") === "websocket") {
        return await connectDevicePresenceWebSocket(request, env);
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

function joinLandingPage(code: string, env: Env): Response {
  const webBase = (env.WEB_APP_BASE_URL || "http://localhost:8080").replace(/\/$/, "");
  const target = `${webBase}/?code=${encodeURIComponent(code)}`;
  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta http-equiv="refresh" content="0;url=${target}" />
  <title>加入传输房间</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      font-family: "Segoe UI", system-ui, sans-serif;
      background: linear-gradient(160deg, #0B1F2A, #163A2E);
      color: #E8F5EF;
    }
    a { color: #3DDC97; }
    .card {
      max-width: 28rem;
      padding: 2rem;
      border-radius: 1.25rem;
      background: rgba(21, 31, 39, 0.92);
      box-shadow: 0 16px 40px rgba(0,0,0,.35);
      text-align: center;
    }
  </style>
  <script>location.replace(${JSON.stringify(target)});</script>
</head>
<body>
  <div class="card">
    <h1>正在打开网页版</h1>
    <p>房间码：<strong>${code}</strong></p>
    <p>若未自动跳转，请点击 <a href="${target}">进入 Transfer P2P</a></p>
  </div>
</body>
</html>`;
  return new Response(html, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

function debugPage(): Response {
  const html = `<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Transfer P2P Worker 诊断</title>
  <style>
    body { font-family: sans-serif; margin: 24px; line-height: 1.5; }
    button { margin: 8px 8px 8px 0; padding: 10px 16px; font-size: 16px; }
    pre { background: #111; color: #0f0; padding: 12px; overflow: auto; }
  </style>
</head>
<body>
  <h1>Transfer P2P Worker 诊断</h1>
  <p>在手机浏览器点下面按钮，测试 Worker 是否正常。</p>
  <button onclick="runGet()">测试 GET /</button>
  <button onclick="runPost()">测试 POST /rooms</button>
  <pre id="out">等待测试...</pre>
  <script>
    const out = document.getElementById("out");
    async function runGet() {
      out.textContent = "GET / 请求中...";
      try {
        const res = await fetch("/");
        const text = await res.text();
        out.textContent = "GET / => HTTP " + res.status + "\\n" + text;
      } catch (e) {
        out.textContent = "GET / 失败: " + e;
      }
    }
    async function runPost() {
      out.textContent = "POST /rooms 请求中...";
      try {
        const res = await fetch("/rooms", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
        });
        const text = await res.text();
        out.textContent = "POST /rooms => HTTP " + res.status + "\\n" + text;
      } catch (e) {
        out.textContent = "POST /rooms 失败: " + e;
      }
    }
  </script>
</body>
</html>`;
  return new Response(html, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

const CONTENT_WORDS = 3;

async function generateRoomCode(): Promise<string> {
  const content: string[] = [];
  for (let i = 0; i < CONTENT_WORDS; i++) {
    content.push(ROOM_WORDS[Math.floor(Math.random() * ROOM_WORDS.length)]);
  }
  const check = await checksumWord(content);
  return [...content, check].join("-");
}

/** Checksum word = word at SHA-256(first CONTENT_WORDS words)[0] % ROOM_WORDS.length. */
async function checksumWord(contentWords: string[]): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(contentWords.join("-")),
  );
  const bytes = new Uint8Array(digest);
  const index = bytes[0] % ROOM_WORDS.length;
  return ROOM_WORDS[index];
}

/** Validate a full code including the checksum word (shared algorithm with the client). */
async function isValidRoomCode(input: string): Promise<boolean> {
  const words = input.trim().toLowerCase().split("-");
  if (words.length !== CONTENT_WORDS + 1) return false;
  const content = words.slice(0, CONTENT_WORDS);
  if (content.some((w) => !ROOM_WORDS.includes(w))) return false;
  return (await checksumWord(content)) === words[CONTENT_WORDS];
}

async function createRoom(_request: Request, env: Env): Promise<Response> {
  return json(await createRoomInternal(env));
}

async function createRoomInternal(env: Env): Promise<{
  code: string;
  joinUrl: string;
  wsUrl: string;
  expiresAt: number;
}> {
  const code = await generateRoomCode();
  const id = env.ROOM.idFromName(code);
  const stub = env.ROOM.get(id);
  const ttl = Number(env.ROOM_TTL_SECONDS || "3600");
  const expiresAt = Date.now() + ttl * 1000;
  await stub.fetch("https://room/init", {
    method: "POST",
    body: JSON.stringify({ code, expiresAt }),
  });
  const base = env.PUBLIC_BASE_URL.replace(/\/$/, "");
  return {
    code,
    joinUrl: `${base}/join/${code}`,
    wsUrl: toWebSocketUrl(base, `/rooms/${code}/ws`),
    expiresAt,
  };
}

async function joinRoom(code: string, env: Env): Promise<Response> {
  if (!(await isValidRoomCode(code))) {
    return json({ error: "Room code failed checksum" }, 400);
  }
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
    wsUrl: toWebSocketUrl(base, `/rooms/${code}/ws`),
    expiresAt: status.expiresAt,
  });
}

/** Browser WebSocket needs ws/wss; http(s) breaks wasm/web clients. */
function toWebSocketUrl(httpBase: string, path: string): string {
  const base = httpBase.replace(/\/$/, "");
  const wsBase = base.startsWith("https://")
    ? `wss://${base.slice("https://".length)}`
    : base.startsWith("http://")
      ? `ws://${base.slice("http://".length)}`
      : base;
  return `${wsBase}${path.startsWith("/") ? path : `/${path}`}`;
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

async function issueIceServers(): Promise<Response> {
  return json({
    iceServers: [
      { urls: ["stun:stun.cloudflare.com:3478"] },
      { urls: ["stun:stun.l.google.com:19302"] },
    ],
    ttl: 3600,
  });
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
  body.pairingCode = body.pairingCode.trim().toUpperCase();
  const id = env.ROOM.idFromName(`device-registry`);
  const stub = env.ROOM.get(id);
  const response = await stub.fetch("https://room/device-claim", {
    method: "POST",
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const reason = await response.text();
    if (reason.includes("expired")) {
      return json({ error: "配对码已过期，请让对方重新生成" }, 400);
    }
    if (reason.includes("not found") || reason.includes("invalid")) {
      return json({ error: "配对码不正确，请核对 6 位大写字母或数字" }, 400);
    }
    if (reason.includes("self")) {
      return json({ error: "不能在本机登记自己的配对码" }, 400);
    }
    return json({ error: "配对失败，请重试" }, 400);
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

async function createDeviceInvite(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{
    fromDeviceId: string;
    toDeviceId: string;
    fromDisplayName: string;
  }>();
  const registryId = env.ROOM.idFromName("device-registry");
  const registryStub = env.ROOM.get(registryId);
  const trustResponse = await registryStub.fetch(
    `https://room/device-trust-check?from=${encodeURIComponent(body.fromDeviceId)}&to=${encodeURIComponent(body.toDeviceId)}`,
  );
  if (!trustResponse.ok) {
    return json({ error: "目标设备不在信任列表中" }, 403);
  }

  const room = await createRoomInternal(env);
  const inviteId = `inv-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const invite = {
    inviteId,
    code: room.code,
    wsUrl: room.wsUrl,
    fromDeviceId: body.fromDeviceId,
    fromDisplayName: body.fromDisplayName,
    toDeviceId: body.toDeviceId,
    expiresAt: room.expiresAt,
  };
  await registryStub.fetch("https://room/device-invite", {
    method: "POST",
    body: JSON.stringify(invite),
  });
  return json({
    ...room,
    inviteId,
    toDeviceId: body.toDeviceId,
  });
}

async function getPendingInvite(deviceId: string, env: Env): Promise<Response> {
  const id = env.ROOM.idFromName("device-registry");
  const stub = env.ROOM.get(id);
  const response = await stub.fetch(`https://room/device-invites/${deviceId}`);
  if (!response.ok) {
    return json(null);
  }
  return json(await response.json());
}

async function consumeDeviceInvite(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{ toDeviceId: string }>();
  const id = env.ROOM.idFromName("device-registry");
  const stub = env.ROOM.get(id);
  await stub.fetch("https://room/device-invites-consume", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return json({ ok: true });
}

async function fetchOnlineStatus(request: Request, env: Env): Promise<Response> {
  const id = env.ROOM.idFromName("device-registry");
  const stub = env.ROOM.get(id);
  const response = await stub.fetch("https://room/device-online-status", {
    method: "POST",
    body: await request.text(),
  });
  return json(await response.json());
}

async function connectDevicePresenceWebSocket(
  request: Request,
  env: Env,
): Promise<Response> {
  const id = env.ROOM.idFromName("device-registry");
  const stub = env.ROOM.get(id);
  return stub.fetch("https://room/device-presence-ws", request);
}

function generatePairingCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
}
