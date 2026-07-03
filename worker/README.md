# Transfer P2P Worker

Cloudflare Worker for signaling and STUN ICE server discovery.

## 部署方式（二选一）

### 方式 A：Cloudflare 控制台直连 GitHub（推荐新手）

可以把 **整个 Git 仓库** 连到 Cloudflare，但 Worker 在子目录 `worker/`，需要指定根目录。

1. 打开 [Cloudflare Dashboard → Workers & Pages](https://dash.cloudflare.com/?to=/:account/workers-and-pages)
2. **Create application → Workers → Connect to Git**（或已有 Worker 则 Settings → Builds → Connect）
3. 选择仓库 `Wonghitman/ScaffoldDemo`，分支 `master`
4. **Build 配置**（关键）：

| 配置项 | 值 |
|--------|-----|
| Root directory | `worker` |
| Build command | `npm ci` |
| Deploy command | `npm run deploy` |

5. Worker 名称必须与 `wrangler.toml` 里的 `name = "transfer-p2p"` 一致
6. 首次部署成功后，到 **Settings → Variables** 更新：
   - `PUBLIC_BASE_URL` = `https://transfer-p2p.<你的子域>.workers.dev`

之后每次 push 到 `master` 且 `worker/` 有改动，Cloudflare 会自动部署。

### 方式 B：GitHub Actions（已配置）

仓库已包含 `.github/workflows/deploy-worker.yml`。

1. Cloudflare 创建 API Token：[My Profile → API Tokens](https://dash.cloudflare.com/profile/api-tokens)  
   模板选 **Edit Cloudflare Workers**，权限需包含 Workers Scripts + Durable Objects
2. 查 Account ID：Dashboard 右侧 Overview 或 `npx wrangler whoami`
3. GitHub 仓库 **Settings → Secrets and variables → Actions** 添加：
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
4. Push 到 `master` 即自动部署（或 Actions 页手动 Run workflow）

### 方式 C：本地 Wrangler

```bash
cd worker
npm ci
npx wrangler login
npx wrangler deploy
```

## 部署后

1. 记下 Worker URL，例如 `https://transfer-p2p.xxx.workers.dev`
2. 修改 Android 端 `shared/.../config/AppConfig.kt`：
   ```kotlin
   const val DEFAULT_SIGNALING_BASE_URL = "https://transfer-p2p.xxx.workers.dev"
   ```

## API 端点

- `POST /rooms` — 创建信令房间
- `GET /rooms/:code` — 加入房间元数据
- `GET /rooms/:code/ws` — WebSocket 信令
- `POST /ice-servers` — STUN ICE 服务器列表
- `POST /devices/register` / `claim` — 持久设备配对

## 本地开发

```bash
npm run dev
```
