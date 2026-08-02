package com.oneturn.transfer.platform

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Kotlin/Wasm `js()` must not be typed as [Boolean] directly — the value often
 * fails Kotlin boolean checks even when the JS expression is true. Use strings.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun rtcPeerConnectionTypeof(): String =
    js("(typeof globalThis.RTCPeerConnection)")

@OptIn(ExperimentalWasmJsInterop::class)
private fun secureContextFlag(): String =
    js(
        """(typeof globalThis.isSecureContext === 'boolean'
            ? (globalThis.isSecureContext ? 'true' : 'false')
            : 'unknown')""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun currentHref(): String =
    js("(typeof location !== 'undefined' && location.href) ? location.href : ''")

actual fun requireWebRtcAvailable() {
    if (rtcPeerConnectionTypeof() == "function") return
    val secure = secureContextFlag()
    val href = currentHref()
    val hint = when (secure) {
        "false" ->
            "当前不是安全上下文（$href）。请用 http://localhost:8080 或 https 打开"
        else ->
            "浏览器未提供 RTCPeerConnection（typeof=${rtcPeerConnectionTypeof()}, isSecureContext=$secure, href=$href）。请关闭禁用 WebRTC 的隐私扩展后重试"
    }
    error("WebRTC 不可用：$hint")
}
