package com.oneturn.transfer.platform

import com.oneturn.transfer.signaling.SignalingHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual class SignalingHttp {
    actual suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        request(url, method = "GET")
    }

    actual suspend fun post(url: String, body: String?): String = withContext(Dispatchers.IO) {
        request(url, method = "POST", body = body ?: "{}")
    }

    private fun request(url: String, method: String, body: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 60_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "TransferP2P-Android")
            useCaches = false
            instanceFollowRedirects = true
            doInput = true
            if (method == "POST") {
                doOutput = true
                outputStream.use { it.write(body.orEmpty().toByteArray(Charsets.UTF_8)) }
            }
        }

        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw SignalingHttpException(code, text.ifBlank { connection.responseMessage.orEmpty() })
            }
            text
        } finally {
            connection.disconnect()
        }
    }
}

actual fun createSignalingHttp(): SignalingHttp = SignalingHttp()
