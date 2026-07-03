package com.oneturn.transfer.platform

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
