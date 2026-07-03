package com.oneturn.transfer.signaling

class SignalingHttpException(
    val statusCode: Int,
    val errorBody: String,
) : Exception(formatMessage(statusCode, errorBody)) {
    companion object {
        private fun formatMessage(statusCode: Int, errorBody: String): String {
            val serverMessage = Regex(""""error"\s*:\s*"([^"]+)"""")
                .find(errorBody)
                ?.groupValues
                ?.get(1)
            return when {
                statusCode == 400 && serverMessage?.contains("pairing", ignoreCase = true) == true ->
                    "配对码无效或已过期"
                serverMessage != null -> serverMessage
                errorBody.isNotBlank() -> "HTTP $statusCode: $errorBody"
                else -> "HTTP $statusCode"
            }
        }
    }
}
