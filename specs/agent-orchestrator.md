# Specification: End-to-End Agentic Browsing Orchestrator Pipeline

**Spec ID:** `SPEC-ORCH-001`  
**Status:** VALIDATED  
**Component:** `corvus-android` (Orchestrator & Cross-Component Pipeline)  
**Related Specs:** `SPEC-RPC-001`, `SPEC-REG-001`, `SPEC-JNI-001`, `SPEC-CALIB-001`, `SPEC-SESSION-001`, `SPEC-ROUTE-001`, `SPEC-MEM-001`, `SPEC-UI-001`

---

## 1. Architectural Overview

`CorvusAgentOrchestrator` coordinates the end-to-end observation-to-action loop across the Corvus browser ecosystem:

```
+-------------------------------------------------------------------------+
|                        CorvusAgentOrchestrator                          |
|                                                                         |
|  1. Observe DOM (JSON-RPC 2.0 "dom.observation")                        |
|     +---> GeckoSessionHolder.webExtensionPort                           |
|                                                                         |
|  2. Evaluate System 1 Reflex (ReflexEngine / LayaNativeEngine JNI)      |
|     +---> Tokenize with dynamic [MASK] around [#nodeId] (< 1ms)         |
|     +---> Calibrate logits (T = 1.25) & evaluate NOUL (tau = 0.65)      |
|                                                                         |
|  3. Deterministic 4-Level Routing Cascade (DeterministicRoutingEngine)  |
|     +---> L1 Network -> L2 Context Cap -> L3 Privacy -> L4 Confidence   |
|                                                                         |
|  4a. Fast Path: SYSTEM_1_LAYA (< 40ms budget, S2 remains UNLOADED)      |
|  4b. Deliberate / NOUL Path: SYSTEM_2_LOCAL_SLM or SYSTEM_2_CLOUD_BYOK  |
|      +---> Local SLM drives MemoryTtlStateMachine (60s TTL)             |
|                                                                         |
|  5. Dispatch Action (JSON-RPC 2.0 "action.execute")                     |
|     +---> Preserves GeckoSession state (0 reloads)                      |
|     +---> Handles spec errors (-32001, -32002, -32003)                  |
+-------------------------------------------------------------------------+
```

---

## 2. Typed JSON-RPC 2.0 Domain Contracts (`dev.corvus.browser.rpc`)

All IPC frames exchanged over `WebExtensionPort` must conform to `specs/jsonrpc-contracts.json` (`SPEC-RPC-001`):

1. **`dom.observation`**:
   - Request Params: `DomObservationParams(includeBoundingBoxes: Boolean = false, maxDepth: Int = 32)`
   - Response Result: `DomObservationResult(url: String, title: String, viewport: ViewportInfo, tree: String, nodeCount: Int, interactiveCount: Int)`
2. **`action.execute`**:
   - Request Params: `ActionExecuteParams(nodeId: Int, actionType: ActionType, text: String? = null, key: String? = null, value: String? = null)`
   - Supported `ActionType` values: `click`, `type`, `clear`, `scroll_into_view`, `hover`, `select`
   - Response Result: `ActionExecuteResult(success: Boolean, nodeId: Int, actionType: ActionType, executionTimeMs: Double, errorMessage: String? = null, errorCode: Int? = null)`
   - Error Codes (`SpecErrorCodes`):
     * `-32001`: `NODE_NOT_FOUND`
     * `-32002`: `NODE_GARBAGE_COLLECTED`
     * `-32003`: `NODE_DETACHED`
     * `-32600`: `INVALID_REQUEST`
     * `-32601`: `METHOD_NOT_FOUND`
     * `-32602`: `INVALID_PARAMS`
     * `-32603`: `INTERNAL_ERROR`
3. **`agent.status_update`**:
   - Request Params: `AgentStatusUpdateParams(status: AgentStatus, stepIndex: Int, currentGoal: String? = null, lastLatencyMs: Double? = null)`
   - Response Result: `AgentStatusAck(acknowledged: Boolean = true)`

---

## 3. Dual-System Decision Routing & Execution Invariants

1. **System 1 Fast Reflex Invariant:**
   - When `isReflexCandidate == true` and calibrated confidence `score >= 0.65` (`noul == false`) within local context limits, `DeterministicRoutingEngine` selects `RoutingTarget.SYSTEM_1_LAYA`.
   - The orchestrator resolves the chosen candidate `nodeId` from the pruned DOM tree and dispatches `action.execute` immediately without waking System 2 (`MemoryTtlStateMachine.state` remains `UNLOADED`).
2. **NOUL Safety Escalation Invariant:**
   - When `score < 0.65` (`noul == true`, `choice == -1`) or when the task requires multi-step deliberate reasoning (`isReflexCandidate == false`), the orchestrator escalates to System 2 (`SYSTEM_2_LOCAL_SLM` or `SYSTEM_2_CLOUD_BYOK`).
3. **System 2 Local SLM Memory TTL Invariant:**
   - When routing to `SYSTEM_2_LOCAL_SLM`, the orchestrator emits `MemoryEvent.RequestS2` (transitioning `UNLOADED -> LOADING -> ACTIVE` or cancelling `IDLE_COUNTDOWN -> ACTIVE`), executes the `System2Planner`, and emits `MemoryEvent.InferenceComplete` to start the 60-second `IDLE_COUNTDOWN`.
4. **Zero-Reload Session Invariant:**
   - Throughout observation, routing, inference, action dispatch, and UI layout transitions (`releaseSession` / `setSession`), `reloadCount` on the active session must remain `0`.
