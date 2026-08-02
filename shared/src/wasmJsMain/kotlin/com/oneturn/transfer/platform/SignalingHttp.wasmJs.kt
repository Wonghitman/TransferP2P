package com.oneturn.transfer.platform

import com.oneturn.transfer.signaling.SignalingHttpException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent

actual class SignalingHttp {
    private val client = createHttpClient()

    actual suspend fun get(url: String): String {
        val response = client.get(url)
        val text = response.bodyAsText()
        val code = response.status.value
        if (code !in 200..299) {
            throw SignalingHttpException(code, text.ifBlank { response.status.description })
        }
        return text
    }

    actual suspend fun post(url: String, body: String?): String {
        val response = client.post(url) {
            setBody(TextContent(body ?: "{}", ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        val code = response.status.value
        if (code !in 200..299) {
            throw SignalingHttpException(code, text.ifBlank { response.status.description })
        }
        return text
    }
}

actual fun createSignalingHttp(): SignalingHttp = SignalingHttp()