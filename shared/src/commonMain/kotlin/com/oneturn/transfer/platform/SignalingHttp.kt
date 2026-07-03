package com.oneturn.transfer.platform

expect class SignalingHttp {
    suspend fun get(url: String): String
    suspend fun post(url: String, body: String? = null): String
}

expect fun createSignalingHttp(): SignalingHttp
