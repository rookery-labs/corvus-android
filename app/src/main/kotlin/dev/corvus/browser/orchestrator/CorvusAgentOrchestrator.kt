package dev.corvus.browser.orchestrator

import dev.corvus.browser.routing.*
import dev.corvus.browser.rpc.*
import dev.corvus.browser.session.CorvusSessionManager
import dev.corvus.laya.ReflexEngine

fun interface System2Planner {
    suspend fun planDeliberateAction(
        goal: String,
        observation: DomObservationResult,
        candidateIds: List<Int>
    ): ActionExecuteParams
}

data class StepExecutionResult(
    val success: Boolean,
    val routingTarget: RoutingTarget,
    val noulTriggered: Boolean,
    val observation: DomObservationResult?,
    val executedAction: ActionExecuteResult?,
    val latencyMs: Double,
    val errorCode: Int? = null,
    val errorMessage: String? = null
)

class CorvusAgentOrchestrator(
    private val sessionManager: CorvusSessionManager,
    private val routingEngine: DeterministicRoutingEngine,
    private val reflexEngine: ReflexEngine,
    private val memoryStateMachine: MemoryTtlStateMachine,
    private val system2Planner: System2Planner = System2Planner { _, _, candidates ->
        ActionExecuteParams(
            nodeId = candidates.firstOrNull() ?: 1,
            actionType = ActionType.CLICK
        )
    }
) {
    private var stepCounter: Int = 0
    private val candidateMarkerRegex = Regex("""\[#(\d+)]""")

    suspend fun executeStep(
        sessionId: String,
        goal: String,
        candidateLogits: FloatArray,
        plannedActionType: ActionType = ActionType.CLICK,
        plannedText: String? = null,
        plannedValue: String? = null,
        isReflexCandidate: Boolean = true,
        networkState: NetworkState = NetworkState.ONLINE,
        userPreferences: UserPreferences = UserPreferences(),
        maxDepth: Int = 32
    ): StepExecutionResult {
        val startNs = System.nanoTime()
        val currentStep = stepCounter++
        val session = sessionManager.getSession(sessionId)
            ?: return StepExecutionResult(
                success = false,
                routingTarget = RoutingTarget.SYSTEM_1_LAYA,
                noulTriggered = false,
                observation = null,
                executedAction = null,
                latencyMs = elapsedMs(startNs),
                errorCode = SpecErrorCodes.INTERNAL_ERROR,
                errorMessage = "Session not found: $sessionId"
            )

        // 1. Emit OBSERVING status & request DOM observation over JSON-RPC 2.0
        sendStatusUpdate(sessionId, AgentStatus.OBSERVING, currentStep, goal)
        val obsReqJson = JsonRpcValidator.createObservationRequest(id = "obs-$currentStep", maxDepth = maxDepth)
        val obsRawResp = session.sendRpc(obsReqJson)
        val obsResp = JsonRpcValidator.parseResponse(obsRawResp).getOrElse {
            return StepExecutionResult(
                success = false,
                routingTarget = RoutingTarget.SYSTEM_1_LAYA,
                noulTriggered = false,
                observation = null,
                executedAction = null,
                latencyMs = elapsedMs(startNs),
                errorCode = SpecErrorCodes.PARSE_ERROR,
                errorMessage = it.message
            )
        }
        val observation = JsonRpcValidator.decodeObservationResult(obsResp).getOrElse {
            return StepExecutionResult(
                success = false,
                routingTarget = RoutingTarget.SYSTEM_1_LAYA,
                noulTriggered = false,
                observation = null,
                executedAction = null,
                latencyMs = elapsedMs(startNs),
                errorCode = obsResp.error?.code ?: SpecErrorCodes.INTERNAL_ERROR,
                errorMessage = it.message
            )
        }

        // 2. Extract candidate nodeIds from pruned tree and evaluate System 1 Reflex
        val candidateNodeIds = extractCandidateIds(observation.tree)
        val prompt = "${observation.tree}\nGoal: $goal"
        val reflexEval = reflexEngine.evaluate(
            prompt = prompt,
            candidateNodeIds = candidateNodeIds,
            rawLogits = candidateLogits
        )
        val noulTriggered = reflexEval.decision.noul
        val reflexScore = if (noulTriggered) 0.0f else reflexEval.decision.score
        val estimatedTokens = maxOf(observation.nodeCount * 8, reflexEval.batch.inputIds.count { it != 0L })

        // 3. Route through 4-Level Deterministic Routing Cascade
        val routingTarget = routingEngine.route(
            request = TaskRequest(
                instruction = goal,
                tokenCount = estimatedTokens,
                isReflexCandidate = isReflexCandidate && !noulTriggered,
                reflexConfidenceScore = reflexScore
            ),
            network = networkState,
            preferences = userPreferences
        )

        // 4. Plan action via System 1 Fast Path or System 2 Deliberate Path
        val actionParams: ActionExecuteParams = if (routingTarget == RoutingTarget.SYSTEM_1_LAYA) {
            sendStatusUpdate(sessionId, AgentStatus.THINKING_S1, currentStep, goal)
            val targetNodeId = reflexEval.selectedNodeId ?: candidateNodeIds.firstOrNull() ?: 1
            ActionExecuteParams(
                nodeId = targetNodeId,
                actionType = plannedActionType,
                text = plannedText,
                value = plannedValue
            )
        } else {
            sendStatusUpdate(sessionId, AgentStatus.THINKING_S2, currentStep, goal)
            if (routingTarget == RoutingTarget.SYSTEM_2_LOCAL_SLM) {
                if (memoryStateMachine.state == MemoryState.UNLOADED) {
                    memoryStateMachine.processEvent(MemoryEvent.RequestS2)
                    memoryStateMachine.processEvent(MemoryEvent.ModelReady)
                } else if (memoryStateMachine.state == MemoryState.IDLE_COUNTDOWN) {
                    memoryStateMachine.processEvent(MemoryEvent.RequestS2)
                }
            }
            val planned = system2Planner.planDeliberateAction(goal, observation, candidateNodeIds)
            if (routingTarget == RoutingTarget.SYSTEM_2_LOCAL_SLM) {
                memoryStateMachine.processEvent(MemoryEvent.InferenceComplete)
            }
            planned
        }

        // 5. Execute action over JSON-RPC 2.0 bus
        sendStatusUpdate(sessionId, AgentStatus.EXECUTING, currentStep, goal)
        val execResult = dispatchDirectAction(sessionId, actionParams)
        val totalLatencyMs = elapsedMs(startNs)

        val finalStatus = if (execResult.success) AgentStatus.COMPLETED else AgentStatus.ERROR
        sendStatusUpdate(sessionId, finalStatus, currentStep, goal, totalLatencyMs)

        return StepExecutionResult(
            success = execResult.success,
            routingTarget = routingTarget,
            noulTriggered = noulTriggered,
            observation = observation,
            executedAction = execResult,
            latencyMs = totalLatencyMs,
            errorCode = execResult.errorCode,
            errorMessage = execResult.errorMessage
        )
    }

    suspend fun dispatchDirectAction(
        sessionId: String,
        params: ActionExecuteParams
    ): ActionExecuteResult {
        val session = sessionManager.getSession(sessionId)
            ?: return ActionExecuteResult(
                success = false,
                nodeId = params.nodeId,
                actionType = params.actionType,
                executionTimeMs = 0.0,
                errorMessage = "Session not found: $sessionId",
                errorCode = SpecErrorCodes.INTERNAL_ERROR
            )

        val reqJson = JsonRpcValidator.createActionRequest(id = "act-${stepCounter++}", params = params)
        val rawResp = session.sendRpc(reqJson)
        val parsedResp = JsonRpcValidator.parseResponse(rawResp).getOrElse {
            return ActionExecuteResult(
                success = false,
                nodeId = params.nodeId,
                actionType = params.actionType,
                executionTimeMs = 0.0,
                errorMessage = it.message,
                errorCode = SpecErrorCodes.PARSE_ERROR
            )
        }
        return JsonRpcValidator.decodeActionResult(parsedResp).getOrElse {
            ActionExecuteResult(
                success = false,
                nodeId = params.nodeId,
                actionType = params.actionType,
                executionTimeMs = 0.0,
                errorMessage = parsedResp.error?.message ?: it.message,
                errorCode = parsedResp.error?.code ?: SpecErrorCodes.ACTION_FAILED
            )
        }
    }

    private suspend fun sendStatusUpdate(
        sessionId: String,
        status: AgentStatus,
        stepIndex: Int,
        goal: String,
        lastLatencyMs: Double? = null
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val reqJson = JsonRpcValidator.createStatusUpdateRequest(
            id = "status-$stepIndex-${status.name.lowercase()}",
            params = AgentStatusUpdateParams(
                status = status,
                stepIndex = stepIndex,
                currentGoal = goal,
                lastLatencyMs = lastLatencyMs
            )
        )
        session.sendRpc(reqJson)
    }

    private fun extractCandidateIds(tree: String): List<Int> {
        return candidateMarkerRegex.findAll(tree)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
    }

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0
}
