package dev.corvus.browser.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class JsonRpcRequestMessage(
    val jsonrpc: String,
    val method: String,
    val id: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String
)

@Serializable
data class JsonRpcResponseMessage(
    val jsonrpc: String,
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

object JsonRpcValidator {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseAndValidateRequest(rawJson: String): Result<JsonRpcRequestMessage> {
        return try {
            val req = json.decodeFromString<JsonRpcRequestMessage>(rawJson)
            if (req.jsonrpc != "2.0") {
                Result.failure(IllegalArgumentException("Invalid jsonrpc version: ${req.jsonrpc}"))
            } else if (req.method.isBlank()) {
                Result.failure(IllegalArgumentException("Empty method"))
            } else {
                Result.success(req)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseResponse(rawJson: String): Result<JsonRpcResponseMessage> {
        return try {
            val resp = json.decodeFromString<JsonRpcResponseMessage>(rawJson)
            if (resp.jsonrpc != "2.0") {
                Result.failure(IllegalArgumentException("Invalid jsonrpc version: ${resp.jsonrpc}"))
            } else {
                Result.success(resp)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
