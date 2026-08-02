@file:OptIn(ExperimentalWasmJsInterop::class)

package com.oneturn.transfer.platform

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await
import okio.ByteString.Companion.toByteString

internal data class BrowserPickedBytes(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

private fun pickFilePromise(): Promise<JsAny?> =
    js(
        """
        (new Promise(function(resolve, reject) {
          var input = document.createElement('input');
          input.type = 'file';
          input.style.display = 'none';
          document.body.appendChild(input);
          var cleanup = function() {
            if (input.parentNode) document.body.removeChild(input);
          };
          input.onchange = function() {
            var file = input.files && input.files[0];
            cleanup();
            if (!file) { resolve(null); return; }
            file.arrayBuffer().then(function(buf) {
              var bytes = Array.from(new Uint8Array(buf));
              resolve({
                name: file.name,
                mimeType: file.type || 'application/octet-stream',
                sizeBytes: file.size,
                bytes: bytes
              });
            }).catch(reject);
          };
          input.addEventListener('cancel', function() { cleanup(); resolve(null); });
          input.click();
        }))
        """,
    )

private fun pickedName(value: JsAny): String =
    js("value.name")

private fun pickedMime(value: JsAny): String =
    js("(value.mimeType || 'application/octet-stream')")

private fun pickedSize(value: JsAny): Double =
    js("value.sizeBytes")

private fun pickedBytesLength(value: JsAny): Int =
    js("value.bytes.length")

private fun pickedByteAt(value: JsAny, index: Int): Int =
    js("value.bytes[index]")

internal suspend fun pickBrowserFile(): BrowserPickedBytes? {
    val result = pickFilePromise().await<JsAny?>() ?: return null
    val length = pickedBytesLength(result)
    val bytes = ByteArray(length) { i -> pickedByteAt(result, i).toByte() }
    return BrowserPickedBytes(
        name = pickedName(result),
        mimeType = pickedMime(result),
        sizeBytes = pickedSize(result).toLong(),
        bytes = bytes,
    )
}

private fun downloadBase64(fileName: String, mimeType: String, base64: String): Unit =
    js(
        """
        {
          var bin = atob(base64);
          var arr = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
          var blob = new Blob([arr], { type: mimeType || 'application/octet-stream' });
          var url = URL.createObjectURL(blob);
          var a = document.createElement('a');
          a.href = url;
          a.download = fileName;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
        }
        """,
    )

internal fun downloadBrowserFile(fileName: String, mimeType: String, bytes: ByteArray) {
    downloadBase64(fileName, mimeType, bytes.toByteString().base64())
}

private fun locationHref(): String =
    js("window.location.href")

/** Parse join room code from `?code=` or `/join/{code}` in the browser URL. */
fun browserJoinCodeFromLocation(): String? {
    val href = locationHref()
    val url = href.substringBefore('#')
    val pathMatch = Regex("""/join/([^/?#]+)""").find(url)
    if (pathMatch != null) {
        return pathMatch.groupValues[1].trim().lowercase()
    }
    val query = url.substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return null
    return query
        .split('&')
        .mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            if (key == "code" && value.isNotBlank()) value.trim().lowercase() else null
        }
        .firstOrNull()
}
