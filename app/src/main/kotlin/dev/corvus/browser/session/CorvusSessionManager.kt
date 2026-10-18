package dev.corvus.browser.session

import dev.corvus.browser.rpc.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

data class ContextualIdentity(
    val id: String,
    val name: String,
    val color: String,
    val isAgentIsolated: Boolean = false
)

fun interface WebExtensionPort {
    suspend fun sendRpcMessage(rawRequestJson: String): String
}

interface GeckoSessionHolder {
    val sessionId: String
    val currentUrl: String
    val isAttached: Boolean
    val identity: ContextualIdentity
    val reloadCount: Int
    fun releaseView(): Boolean
    fun attachView(): Boolean
    fun bindWebExtensionPort(port: WebExtensionPort)
    suspend fun sendRpc(rawRequestJson: String): String
}

class MockGeckoSession(
    override val sessionId: String,
    override var currentUrl: String,
    override val identity: ContextualIdentity
) : GeckoSessionHolder {
    override var isAttached: Boolean = true
        private set

    override var reloadCount: Int = 0
        private set

    private var port: WebExtensionPort? = null

    override fun releaseView(): Boolean {
        isAttached = false
        // State, DOM, JS execution remain intact; visual surface detached
        return true
    }

    override fun attachView(): Boolean {
        isAttached = true
        // Rebound without reload (F5 reload count does NOT increment)
        return true
    }

    fun triggerReload() {
        reloadCount++
    }

    override fun bindWebExtensionPort(port: WebExtensionPort) {
        this.port = port
    }

    override suspend fun sendRpc(rawRequestJson: String): String {
        val activePort = port ?: throw IllegalStateException("No WebExtensionPort bound to session $sessionId")
        return activePort.sendRpcMessage(rawRequestJson)
    }
}

class CorvusSessionManager {
    private val sessions = mutableMapOf<String, GeckoSessionHolder>()

    fun createSession(
        sessionId: String,
        initialUrl: String,
        identity: ContextualIdentity = ContextualIdentity("agent_isolated", "Agent Sandbox", "#3388FF", true)
    ): GeckoSessionHolder {
        val session = MockGeckoSession(sessionId, initialUrl, identity)
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): GeckoSessionHolder? = sessions[sessionId]

    fun releaseSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        return session.releaseView()
    }

    fun setSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        return session.attachView()
    }
}

/**
 * In-memory WebExtension JSON-RPC 2.0 host modeling `corvus-dom-extension` behavior
 * (`SPEC-DOM-001`, `SPEC-REG-001`, `SPEC-RPC-001`) for JVM integration testing.
 */
class InMemoryWebExtensionHost(
    var url: String = "https://corvus.dev",
    var title: String = "Corvus Page"
) : WebExtensionPort {

    data class SimulatedElement(
        val id: Int,
        val tag: String,
        val role: String? = null,
        var text: String? = null,
        val attributes: MutableMap<String, String> = mutableMapOf(),
        val isInteractive: Boolean = true,
        val options: List<String> = emptyList(),
        var value: String = "",
        var isConnected: Boolean = true,
        var isGarbageCollected: Boolean = false,
        var clicked: Boolean = false,
        var hovered: Boolean = false,
        var scrolledIntoView: Boolean = false
    )

    private val elements = mutableMapOf<Int, SimulatedElement>()
    private var nextNodeId = 1
    val statusUpdates = mutableListOf<AgentStatusUpdateParams>()

    fun registerElement(
        tag: String,
        role: String? = null,
        text: String? = null,
        attributes: Map<String, String> = emptyMap(),
        isInteractive: Boolean = true,
        options: List<String> = emptyList()
    ): Int {
        val id = nextNodeId++
        elements[id] = SimulatedElement(
            id = id,
            tag = tag.lowercase(),
            role = role,
            text = text,
            attributes = attributes.toMutableMap(),
            isInteractive = isInteractive,
            options = options,
            value = attributes["value"] ?: ""
        )
        return id
    }

    fun detachElement(nodeId: Int) {
        elements[nodeId]?.isConnected = false
    }

    fun simulateGarbageCollection(nodeId: Int) {
        elements[nodeId]?.isGarbageCollected = true
    }

    fun getElementValue(nodeId: Int): String? = elements[nodeId]?.value

    fun wasClicked(nodeId: Int): Boolean = elements[nodeId]?.clicked == true

    fun wasHovered(nodeId: Int): Boolean = elements[nodeId]?.hovered == true

    override suspend fun sendRpcMessage(rawRequestJson: String): String {
        val reqResult = JsonRpcValidator.parseAndValidateRequest(rawRequestJson)
        if (reqResult.isFailure) {
            return encodeError("null", SpecErrorCodes.INVALID_REQUEST, reqResult.exceptionOrNull()?.message ?: "Invalid request")
        }
        val req = reqResult.getOrThrow()

        return when (req.method) {
            "dom.observation" -> {
                val activeNodes = elements.values.filter { !it.isGarbageCollected && it.isConnected }
                val lines = mutableListOf<String>()
                for (el in activeNodes) {
                    val rolePart = if (el.role != null) " (role=${el.role})" else ""
                    val textPart = if (!el.text.isNullOrEmpty()) " \"${el.text}\"" else ""
                    val attrsPart = if (el.attributes.isNotEmpty()) {
                        " [" + el.attributes.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" } + "]"
                    } else ""
                    lines.add("[#${el.id}] ${el.tag}$rolePart$textPart$attrsPart")
                }
                val obs = DomObservationResult(
                    url = url,
                    title = title,
                    viewport = ViewportInfo(1080, 1920, 0, 0),
                    tree = lines.joinToString("\n"),
                    nodeCount = activeNodes.size,
                    interactiveCount = activeNodes.count { it.isInteractive }
                )
                val resp = JsonRpcResponseMessage(
                    jsonrpc = "2.0",
                    id = req.id,
                    result = JsonRpcValidator.json.encodeToJsonElement(obs)
                )
                JsonRpcValidator.json.encodeToString(resp)
            }

            "action.execute" -> {
                val paramsResult = JsonRpcValidator.decodeActionParams(req)
                if (paramsResult.isFailure) {
                    return encodeError(req.id, SpecErrorCodes.INVALID_PARAMS, "Invalid action.execute params")
                }
                val params = paramsResult.getOrThrow()
                val el = elements[params.nodeId]

                if (el == null) {
                    val failData = ActionExecuteResult(
                        success = false,
                        nodeId = params.nodeId,
                        actionType = params.actionType,
                        executionTimeMs = 0.2,
                        errorMessage = "Node [#${params.nodeId}] was never registered in registry (not found)",
                        errorCode = SpecErrorCodes.NODE_NOT_FOUND
                    )
                    return encodeError(req.id, SpecErrorCodes.NODE_NOT_FOUND, failData.errorMessage!!, failData)
                }

                if (el.isGarbageCollected) {
                    val failData = ActionExecuteResult(
                        success = false,
                        nodeId = params.nodeId,
                        actionType = params.actionType,
                        executionTimeMs = 0.2,
                        errorMessage = "Node [#${params.nodeId}] was registered but has been garbage collected",
                        errorCode = SpecErrorCodes.NODE_GARBAGE_COLLECTED
                    )
                    return encodeError(req.id, SpecErrorCodes.NODE_GARBAGE_COLLECTED, failData.errorMessage!!, failData)
                }

                if (!el.isConnected) {
                    val failData = ActionExecuteResult(
                        success = false,
                        nodeId = params.nodeId,
                        actionType = params.actionType,
                        executionTimeMs = 0.2,
                        errorMessage = "Node [#${params.nodeId}] is detached from DOM",
                        errorCode = SpecErrorCodes.NODE_DETACHED
                    )
                    return encodeError(req.id, SpecErrorCodes.NODE_DETACHED, failData.errorMessage!!, failData)
                }

                when (params.actionType) {
                    ActionType.CLICK -> {
                        el.clicked = true
                        // Submit guard prevents full page reload per SPEC-SESSION-001 / R3
                    }
                    ActionType.TYPE -> {
                        el.value += (params.text ?: "")
                        el.attributes["value"] = el.value
                    }
                    ActionType.CLEAR -> {
                        el.value = ""
                        el.attributes.remove("value")
                    }
                    ActionType.SCROLL_INTO_VIEW -> {
                        el.scrolledIntoView = true
                    }
                    ActionType.HOVER -> {
                        el.hovered = true
                    }
                    ActionType.SELECT -> {
                        if (el.tag != "select") {
                            val failData = ActionExecuteResult(
                                success = false,
                                nodeId = params.nodeId,
                                actionType = params.actionType,
                                executionTimeMs = 0.3,
                                errorMessage = "Target node [#${params.nodeId}] is not a <select> element",
                                errorCode = SpecErrorCodes.ACTION_FAILED
                            )
                            return encodeError(req.id, SpecErrorCodes.ACTION_FAILED, failData.errorMessage!!, failData)
                        }
                        val targetVal = params.value ?: params.text
                        if (targetVal == null || (el.options.isNotEmpty() && targetVal !in el.options)) {
                            val failData = ActionExecuteResult(
                                success = false,
                                nodeId = params.nodeId,
                                actionType = params.actionType,
                                executionTimeMs = 0.3,
                                errorMessage = "Option '$targetVal' not found in <select>",
                                errorCode = SpecErrorCodes.ACTION_FAILED
                            )
                            return encodeError(req.id, SpecErrorCodes.ACTION_FAILED, failData.errorMessage!!, failData)
                        }
                        el.value = targetVal
                        el.attributes["value"] = targetVal
                    }
                }

                val execResult = ActionExecuteResult(
                    success = true,
                    nodeId = params.nodeId,
                    actionType = params.actionType,
                    executionTimeMs = 0.85
                )
                val resp = JsonRpcResponseMessage(
                    jsonrpc = "2.0",
                    id = req.id,
                    result = JsonRpcValidator.json.encodeToJsonElement(execResult)
                )
                JsonRpcValidator.json.encodeToString(resp)
            }

            "agent.status_update" -> {
                val ack = AgentStatusAck(acknowledged = true)
                val resp = JsonRpcResponseMessage(
                    jsonrpc = "2.0",
                    id = req.id,
                    result = JsonRpcValidator.json.encodeToJsonElement(ack)
                )
                JsonRpcValidator.json.encodeToString(resp)
            }

            else -> encodeError(req.id, SpecErrorCodes.METHOD_NOT_FOUND, "Method not found: ${req.method}")
        }
    }

    private fun encodeError(
        id: String,
        code: Int,
        message: String,
        data: ActionExecuteResult? = null
    ): String {
        val resp = JsonRpcResponseMessage(
            jsonrpc = "2.0",
            id = id,
            error = JsonRpcError(
                code = code,
                message = message,
                data = data?.let { JsonRpcValidator.json.encodeToJsonElement(it) }
            )
        )
        return JsonRpcValidator.json.encodeToString(resp)
    }
}
