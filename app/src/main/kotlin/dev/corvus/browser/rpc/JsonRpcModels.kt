package dev.corvus.browser.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

object SpecErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val ACTION_FAILED = -32000
    const val NODE_NOT_FOUND = -32001
    const val NODE_GARBAGE_COLLECTED = -32002
    const val NODE_DETACHED = -32003
}

@Serializable
data class ViewportInfo(
    val width: Int = 1080,
    val height: Int = 1920,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

@Serializable
data class DomObservationParams(
    val includeBoundingBoxes: Boolean = false,
    val maxDepth: Int = 32
)

@Serializable
data class DomObservationResult(
    val url: String,
    val title: String,
    val viewport: ViewportInfo,
    val tree: String,
    val nodeCount: Int,
    val interactiveCount: Int
)

@Serializable
enum class ActionType {
    @SerialName("click") CLICK,
    @SerialName("type") TYPE,
    @SerialName("clear") CLEAR,
    @SerialName("scroll_into_view") SCROLL_INTO_VIEW,
    @SerialName("hover") HOVER,
    @SerialName("select") SELECT
}

@Serializable
data class ActionExecuteParams(
    val nodeId: Int,
    val actionType: ActionType,
    val text: String? = null,
    val key: String? = null,
    val value: String? = null
)

@Serializable
data class ActionExecuteResult(
    val success: Boolean,
    val nodeId: Int,
    val actionType: ActionType,
    val executionTimeMs: Double,
    val errorMessage: String? = null,
    val errorCode: Int? = null
)

@Serializable
enum class AgentStatus {
    IDLE,
    OBSERVING,
    THINKING_S1,
    THINKING_S2,
    EXECUTING,
    COMPLETED,
    ERROR
}

@Serializable
data class AgentStatusUpdateParams(
    val status: AgentStatus,
    val stepIndex: Int,
    val currentGoal: String? = null,
    val lastLatencyMs: Double? = null
)

@Serializable
data class AgentStatusAck(
    val acknowledged: Boolean = true
)

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
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcResponseMessage(
    val jsonrpc: String,
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

object JsonRpcValidator {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    fun createObservationRequest(
        id: String,
        maxDepth: Int = 32,
        includeBoundingBoxes: Boolean = false
    ): String {
        val paramsObj = json.encodeToJsonElement(
            DomObservationParams(includeBoundingBoxes = includeBoundingBoxes, maxDepth = maxDepth)
        ).jsonObject
        val req = JsonRpcRequestMessage(
            jsonrpc = "2.0",
            method = "dom.observation",
            id = id,
            params = paramsObj
        )
        return json.encodeToString(req)
    }

    fun createActionRequest(id: String, params: ActionExecuteParams): String {
        val paramsObj = json.encodeToJsonElement(params).jsonObject
        val req = JsonRpcRequestMessage(
            jsonrpc = "2.0",
            method = "action.execute",
            id = id,
            params = paramsObj
        )
        return json.encodeToString(req)
    }

    fun createStatusUpdateRequest(id: String, params: AgentStatusUpdateParams): String {
        val paramsObj = json.encodeToJsonElement(params).jsonObject
        val req = JsonRpcRequestMessage(
            jsonrpc = "2.0",
            method = "agent.status_update",
            id = id,
            params = paramsObj
        )
        return json.encodeToString(req)
    }

    fun decodeActionParams(req: JsonRpcRequestMessage): Result<ActionExecuteParams> {
        return try {
            val paramsObj = req.params ?: return Result.failure(IllegalArgumentException("Missing params"))
            Result.success(json.decodeFromJsonElement<ActionExecuteParams>(paramsObj))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decodeObservationResult(resp: JsonRpcResponseMessage): Result<DomObservationResult> {
        return try {
            if (resp.error != null) {
                return Result.failure(IllegalStateException("RPC Error ${resp.error.code}: ${resp.error.message}"))
            }
            val resEl = resp.result ?: return Result.failure(IllegalArgumentException("Missing result"))
            Result.success(json.decodeFromJsonElement<DomObservationResult>(resEl))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decodeActionResult(resp: JsonRpcResponseMessage): Result<ActionExecuteResult> {
        return try {
            if (resp.error != null) {
                val dataEl = resp.error.data
                if (dataEl != null) {
                    val decoded = json.decodeFromJsonElement<ActionExecuteResult>(dataEl)
                    return Result.success(decoded.copy(errorCode = decoded.errorCode ?: resp.error.code))
                }
                return Result.failure(IllegalStateException("RPC Error ${resp.error.code}: ${resp.error.message}"))
            }
            val resEl = resp.result ?: return Result.failure(IllegalArgumentException("Missing result"))
            Result.success(json.decodeFromJsonElement<ActionExecuteResult>(resEl))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
