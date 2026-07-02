# Transfer P2P (Android-first)

广域网 P2P 文件传输：WebRTC DataChannel + Cloudflare Worker 信令。

## 模块

- `shared/` - KMP 核心：信令、WebRTC、传输协议、配对、设备身份
- `composeApp/` - Compose Multiplatform Android 应用
- `worker/` - Cloudflare Worker + Durable Object 信令服务

## Android 运行

1. 部署 Worker（见 `worker/README.md`），把 `shared/.../AppConfig.kt` 里的 `DEFAULT_SIGNALING_BASE_URL` 改成你的 Worker 地址
2. Android Studio 打开项目，运行 `composeApp`
3. 发送方：创建房间 → 分享房间码/二维码 → 选文件发送
4. 接收方：输入房间码加入 → 自动接收并校验 SHA-256

## 架构要点

- 文件走 WebRTC DataChannel（DTLS 端到端加密）
- 信令仅交换 SDP/ICE（Cloudflare Durable Object WebSocket）
- STUN: `stun.cloudflare.com`；可选 Cloudflare TURN 兜底
- 临时配对：房间码 + PAKE 校验
- 持久设备：配对码登记信任设备

## 后续（需 iOS 环境）

- 恢复 `iosMain` / `wasmJsMain` target
- iOS 通过 CocoaPods 链接 `WebRTC-SDK` 125.6422.05
- Web 端可退化浏览器原生 `RTCPeerConnection`
