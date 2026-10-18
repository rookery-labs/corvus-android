package dev.corvus.browser

import dev.corvus.browser.orchestrator.*
import dev.corvus.browser.routing.*
import dev.corvus.browser.rpc.*
import dev.corvus.browser.session.ContextualIdentity
import dev.corvus.browser.session.CorvusSessionManager
import dev.corvus.browser.session.InMemoryWebExtensionHost
import dev.corvus.browser.ui.AgentBadgeIndicator
import dev.corvus.browser.ui.ControlCenterLayoutMode
import dev.corvus.browser.ui.ControlCenterStateController
import dev.corvus.laya.JniReflexEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentPipelineIntegrationTest {

    // =========================================================================
    // Tier 1: Feature Coverage (Happy Path)
    // =========================================================================

    @Test
    fun testTypedJsonRpcModelsSerializationAndValidation() {
        val obsReqJson = JsonRpcValidator.createObservationRequest(id = "obs-1", maxDepth = 24)
        val parsedReq = JsonRpcValidator.parseAndValidateRequest(obsReqJson).getOrThrow()
        assertEquals("2.0", parsedReq.jsonrpc)
        assertEquals("dom.observation", parsedReq.method)
        assertEquals("obs-1", parsedReq.id)

        val actionParams = ActionExecuteParams(
            nodeId = 7,
            actionType = ActionType.SELECT,
            value = "price_asc"
        )
        val actReqJson = JsonRpcValidator.createActionRequest(id = "act-1", params = actionParams)
        val parsedActReq = JsonRpcValidator.parseAndValidateRequest(actReqJson).getOrThrow()
        assertEquals("action.execute", parsedActReq.method)
        val decodedParams = JsonRpcValidator.decodeActionParams(parsedActReq).getOrThrow()
        assertEquals(7, decodedParams.nodeId)
        assertEquals(ActionType.SELECT, decodedParams.actionType)
        assertEquals("price_asc", decodedParams.value)

        val statusParams = AgentStatusUpdateParams(
            status = AgentStatus.THINKING_S1,
            stepIndex = 2,
            currentGoal = "Select sort order",
            lastLatencyMs = 8.4
        )
        val statusReqJson = JsonRpcValidator.createStatusUpdateRequest(id = "st-1", params = statusParams)
        val parsedStatus = JsonRpcValidator.parseAndValidateRequest(statusReqJson).getOrThrow()
        assertEquals("agent.status_update", parsedStatus.method)
    }

    @Test
    fun testRealLayaNativeJniTokenizationAndCalibration() {
        JniReflexEngine().use { engine ->
            val domTree = """
                [#1] body
                  [#2] input [type="search" placeholder="Search"]
                  [#3] button "Submit" [type="submit"]
            """.trimIndent()

            val batch = engine.encodePrompt("$domTree Goal: Click Submit", maxLen = 64, injectMasks = true)
            assertEquals(64, batch.inputIds.size)
            assertEquals(101L, batch.inputIds[0]) // [CLS]
            assertTrue(batch.maskIndices.isNotEmpty(), "Expected dynamic [MASK] (103) injected around [#id] markers")
            for (maskIdx in batch.maskIndices) {
                assertEquals(103L, batch.inputIds[maskIdx])
            }

            // High confidence logit vector for 3 candidates
            val probs = engine.calibrate(floatArrayOf(0.1f, 0.2f, 4.5f), temperature = 1.25f)
            assertEquals(3, probs.size)
            val sum = probs.sum()
            assertEquals(1.0f, sum, 1e-4f)

            val decision = engine.evaluate(
                prompt = "$domTree Goal: Click Submit",
                candidateNodeIds = listOf(1, 2, 3),
                rawLogits = floatArrayOf(0.1f, 0.2f, 4.5f)
            )
            assertFalse(decision.decision.noul)
            assertEquals(2, decision.decision.choice)
            assertEquals(3, decision.selectedNodeId)
            assertTrue(decision.decision.score >= 0.65f)
        }
    }

    @Test
    fun testEndToEndSystem1ReflexFastPathExecution() = runTest {
        val sessionManager = CorvusSessionManager()
        val webExt = InMemoryWebExtensionHost(
            url = "https://corvus.dev/login",
            title = "Corvus Login"
        )
        val inputId = webExt.registerElement("input", role = "textbox", attributes = mapOf("type" to "email"), isInteractive = true)
        val submitId = webExt.registerElement("button", role = "button", text = "Sign In", attributes = mapOf("type" to "submit"), isInteractive = true)
        assertEquals(1, inputId)
        assertEquals(2, submitId)

        val session = sessionManager.createSession("tab-main", "https://corvus.dev/login")
        session.bindWebExtensionPort(webExt)

        var s2Released = false
        val memorySm = MemoryTtlStateMachine(defaultTtlSeconds = 60, onModelRelease = { s2Released = true })
        val routingEngine = DeterministicRoutingEngine()
        JniReflexEngine().use { reflexEngine ->
            val orchestrator = CorvusAgentOrchestrator(
                sessionManager = sessionManager,
                routingEngine = routingEngine,
                reflexEngine = reflexEngine,
                memoryStateMachine = memorySm
            )

            // Warm up JVM classloading & kotlinx.serialization descriptors before measuring warm < 40ms SLA
            orchestrator.executeStep(
                sessionId = "tab-main",
                goal = "Focus email input",
                candidateLogits = floatArrayOf(4.2f, 0.1f),
                plannedActionType = ActionType.HOVER
            )

            // Provide confident logits favoring candidate index 1 (submitId = 2)
            val stepResult = orchestrator.executeStep(
                sessionId = "tab-main",
                goal = "Click Sign In button",
                candidateLogits = floatArrayOf(0.1f, 4.8f),
                plannedActionType = ActionType.CLICK
            )

            assertTrue(stepResult.success)
            assertEquals(RoutingTarget.SYSTEM_1_LAYA, stepResult.routingTarget)
            assertFalse(stepResult.noulTriggered)
            assertEquals(submitId, stepResult.executedAction?.nodeId)
            assertEquals(ActionType.CLICK, stepResult.executedAction?.actionType)
            assertTrue(stepResult.latencyMs < 40.0, "System 1 reflex loop must complete within 40ms budget (was ${stepResult.latencyMs}ms)")

            // System 2 must NOT be woken during a confident System 1 reflex action
            assertEquals(MemoryState.UNLOADED, memorySm.state)
            assertFalse(s2Released)
            assertEquals(0, session.reloadCount)
        }
    }

    @Test
    fun testControlCenterResponsiveUiLayoutAndBadgeIndicators() {
        val controller = ControlCenterStateController(screenWidthDp = 412)
        assertEquals(ControlCenterLayoutMode.MOBILE_VERTICAL_SPLIT, controller.state.layoutMode)
        assertEquals(0.50f, controller.state.verticalSplitRatio, 1e-4f)

        // Clamp split ratio to [0.30, 0.70] per SPEC-UI-001
        controller.updateVerticalSplit(0.10f)
        assertEquals(0.30f, controller.state.verticalSplitRatio, 1e-4f)
        controller.updateVerticalSplit(0.85f)
        assertEquals(0.70f, controller.state.verticalSplitRatio, 1e-4f)

        // Reconfigure to tablet >= 600dp
        controller.onScreenWidthChanged(768)
        assertEquals(ControlCenterLayoutMode.TABLET_SIDE_SHEET, controller.state.layoutMode)
        assertEquals(380, controller.state.sideSheetWidthDp)

        // Verify badge mapping
        controller.onAgentStatusChanged(AgentStatus.THINKING_S1)
        assertEquals(AgentBadgeIndicator.PULSING_BLUE_DOT, controller.state.badgeIndicator)

        controller.onAgentStatusChanged(AgentStatus.THINKING_S2)
        assertEquals(AgentBadgeIndicator.CYAN_PROGRESS_RING, controller.state.badgeIndicator)

        controller.onAgentStatusChanged(AgentStatus.ERROR)
        assertEquals(AgentBadgeIndicator.RED_DOT, controller.state.badgeIndicator)

        controller.onAgentStatusChanged(AgentStatus.IDLE)
        assertEquals(AgentBadgeIndicator.NONE, controller.state.badgeIndicator)
    }

    // =========================================================================
    // Tier 2: Boundary & Corner Cases
    // =========================================================================

    @Test
    fun testSpecErrorCodesForMissingGarbageCollectedAndDetachedNodes() = runTest {
        val sessionManager = CorvusSessionManager()
        val webExt = InMemoryWebExtensionHost("https://corvus.dev/app", "App")
        val btn1 = webExt.registerElement("button", role = "button", text = "Action 1", isInteractive = true)
        val btn2 = webExt.registerElement("button", role = "button", text = "Action 2", isInteractive = true)

        val session = sessionManager.createSession("tab-err", "https://corvus.dev/app")
        session.bindWebExtensionPort(webExt)

        val orchestrator = CorvusAgentOrchestrator(
            sessionManager = sessionManager,
            routingEngine = DeterministicRoutingEngine(),
            reflexEngine = JniReflexEngine(),
            memoryStateMachine = MemoryTtlStateMachine()
        )

        // 1. Missing nodeId -> -32001 (NODE_NOT_FOUND)
        val notFoundResp = orchestrator.dispatchDirectAction(
            sessionId = "tab-err",
            params = ActionExecuteParams(nodeId = 9999, actionType = ActionType.CLICK)
        )
        assertFalse(notFoundResp.success)
        assertEquals(SpecErrorCodes.NODE_NOT_FOUND, notFoundResp.errorCode)

        // 2. Garbage-collected node -> -32002 (NODE_GARBAGE_COLLECTED)
        webExt.simulateGarbageCollection(btn1)
        val gcResp = orchestrator.dispatchDirectAction(
            sessionId = "tab-err",
            params = ActionExecuteParams(nodeId = btn1, actionType = ActionType.CLICK)
        )
        assertFalse(gcResp.success)
        assertEquals(SpecErrorCodes.NODE_GARBAGE_COLLECTED, gcResp.errorCode)

        // 3. Detached node -> -32003 (NODE_DETACHED)
        webExt.detachElement(btn2)
        val detachedResp = orchestrator.dispatchDirectAction(
            sessionId = "tab-err",
            params = ActionExecuteParams(nodeId = btn2, actionType = ActionType.CLICK)
        )
        assertFalse(detachedResp.success)
        assertEquals(SpecErrorCodes.NODE_DETACHED, detachedResp.errorCode)
        assertEquals(0, session.reloadCount)
    }

    @Test
    fun testReflexConfidenceExactBoundary06500Vs06499() {
        val engine = JniReflexEngine()
        // Exact threshold 0.65f must be accepted (noul = false)
        val accepted = engine.evaluateProbabilities(floatArrayOf(0.65f, 0.35f), threshold = 0.65f)
        assertFalse(accepted.noul)
        assertEquals(0, accepted.choice)

        // Just below threshold 0.6499f must trigger NOUL (noul = true, choice = -1)
        val rejected = engine.evaluateProbabilities(floatArrayOf(0.6499f, 0.3501f), threshold = 0.65f)
        assertTrue(rejected.noul)
        assertEquals(-1, rejected.choice)
        engine.close()
    }

    // =========================================================================
    // Tier 3: Cross-Feature Combinations
    // =========================================================================

    @Test
    fun testNoulEscalationToSystem2LocalSlmAnd60sMemoryTtlLifecycle() = runTest {
        val sessionManager = CorvusSessionManager()
        val webExt = InMemoryWebExtensionHost("https://corvus.dev/ambiguous", "Ambiguous Dialog")
        val cancelId = webExt.registerElement("button", role = "button", text = "Confirm", isInteractive = true)
        val confirmId = webExt.registerElement("button", role = "button", text = "Confirm Action", isInteractive = true)

        val session = sessionManager.createSession("tab-s2", "https://corvus.dev/ambiguous")
        session.bindWebExtensionPort(webExt)

        var modelReleaseCount = 0
        val memorySm = MemoryTtlStateMachine(
            defaultTtlSeconds = 60,
            onModelRelease = { modelReleaseCount++ }
        )

        // System 2 planner resolves ambiguity by selecting confirmId
        val s2Planner = System2Planner { _, _, _ ->
            ActionExecuteParams(nodeId = confirmId, actionType = ActionType.CLICK)
        }

        val orchestrator = CorvusAgentOrchestrator(
            sessionManager = sessionManager,
            routingEngine = DeterministicRoutingEngine(),
            reflexEngine = JniReflexEngine(),
            memoryStateMachine = memorySm,
            system2Planner = s2Planner
        )

        // Ambiguous logits [1.0f, 1.0f] -> calibrated probabilities [0.5f, 0.5f] < 0.65 -> NOUL!
        val result = orchestrator.executeStep(
            sessionId = "tab-s2",
            goal = "Confirm the dialog action",
            candidateLogits = floatArrayOf(1.0f, 1.0f),
            plannedActionType = ActionType.CLICK,
            networkState = NetworkState.ONLINE,
            userPreferences = UserPreferences(strictPrivacyMode = true)
        )

        assertTrue(result.success)
        assertTrue(result.noulTriggered)
        assertEquals(RoutingTarget.SYSTEM_2_LOCAL_SLM, result.routingTarget)
        assertEquals(confirmId, result.executedAction?.nodeId)
        assertFalse(webExt.wasClicked(cancelId))
        assertTrue(webExt.wasClicked(confirmId))

        // Verify System 2 transitioned to IDLE_COUNTDOWN with 60s TTL after inference completion
        assertEquals(MemoryState.IDLE_COUNTDOWN, memorySm.state)
        assertEquals(60, memorySm.remainingTtl)
        assertEquals(0, modelReleaseCount)

        // Tick 59 seconds -> still IDLE_COUNTDOWN with 1s remaining
        repeat(59) { memorySm.processEvent(MemoryEvent.Tick1s) }
        assertEquals(MemoryState.IDLE_COUNTDOWN, memorySm.state)
        assertEquals(1, memorySm.remainingTtl)
        assertEquals(0, modelReleaseCount)

        // 60th tick -> expires and unloads model weights
        memorySm.processEvent(MemoryEvent.Tick1s)
        assertEquals(MemoryState.UNLOADED, memorySm.state)
        assertEquals(0, memorySm.remainingTtl)
        assertEquals(1, modelReleaseCount)
        assertEquals(0, session.reloadCount)
    }

    @Test
    fun testZeroReloadSessionDetachAndReattachDuringActivePipeline() = runTest {
        val sessionManager = CorvusSessionManager()
        val webExt = InMemoryWebExtensionHost("https://corvus.dev/editor", "Live Editor")
        val inputId = webExt.registerElement("input", role = "textbox", attributes = mapOf("type" to "text"), isInteractive = true)

        val session = sessionManager.createSession(
            sessionId = "tab-isolated",
            initialUrl = "https://corvus.dev/editor",
            identity = ContextualIdentity("agent_isolated", "Agent Sandbox", "#3388FF", isAgentIsolated = true)
        )
        session.bindWebExtensionPort(webExt)

        val orchestrator = CorvusAgentOrchestrator(
            sessionManager = sessionManager,
            routingEngine = DeterministicRoutingEngine(),
            reflexEngine = JniReflexEngine(),
            memoryStateMachine = MemoryTtlStateMachine()
        )

        // Detach visual surface (simulating screen rotation or split-screen resize)
        assertTrue(sessionManager.releaseSession("tab-isolated"))
        assertFalse(session.isAttached)

        // Execute agent step while surface is detached
        val step = orchestrator.executeStep(
            sessionId = "tab-isolated",
            goal = "Type draft note",
            candidateLogits = floatArrayOf(5.0f),
            plannedActionType = ActionType.TYPE,
            plannedText = "Zero-reload preserved text"
        )
        assertTrue(step.success)
        assertEquals("Zero-reload preserved text", webExt.getElementValue(inputId))

        // Reattach visual surface
        assertTrue(sessionManager.setSession("tab-isolated"))
        assertTrue(session.isAttached)
        assertEquals(0, session.reloadCount)
        assertEquals("Zero-reload preserved text", webExt.getElementValue(inputId))
    }

    // =========================================================================
    // Tier 4: Real-World Application Scenarios
    // =========================================================================

    @Test
    fun testMultiStepEcommerceSearchFilterAndCheckoutFlow() = runTest {
        val sessionManager = CorvusSessionManager()
        val webExt = InMemoryWebExtensionHost("https://store.corvus.dev", "Corvus Store")

        val searchInputId = webExt.registerElement("input", role = "searchbox", attributes = mapOf("type" to "search"), isInteractive = true)
        val searchBtnId = webExt.registerElement("button", role = "button", text = "Search", attributes = mapOf("type" to "submit"), isInteractive = true)
        val sortSelectId = webExt.registerElement(
            tag = "select",
            role = "combobox",
            attributes = mapOf("name" to "sort"),
            isInteractive = true,
            options = listOf("relevance", "price_asc", "price_desc")
        )
        val checkoutBtnId = webExt.registerElement("button", role = "button", text = "Checkout", isInteractive = true)

        val session = sessionManager.createSession("tab-shop", "https://store.corvus.dev")
        session.bindWebExtensionPort(webExt)

        val memorySm = MemoryTtlStateMachine(defaultTtlSeconds = 60)
        var s2Invocations = 0
        val s2Planner = System2Planner { goal, _, _ ->
            s2Invocations++
            if (goal.contains("sort", ignoreCase = true)) {
                ActionExecuteParams(nodeId = sortSelectId, actionType = ActionType.SELECT, value = "price_asc")
            } else {
                ActionExecuteParams(nodeId = checkoutBtnId, actionType = ActionType.CLICK)
            }
        }

        val orchestrator = CorvusAgentOrchestrator(
            sessionManager = sessionManager,
            routingEngine = DeterministicRoutingEngine(),
            reflexEngine = JniReflexEngine(),
            memoryStateMachine = memorySm,
            system2Planner = s2Planner
        )

        // Step 1: High-confidence System 1 TYPE into search box (#1)
        val step1 = orchestrator.executeStep(
            sessionId = "tab-shop",
            goal = "Type 'onnx mobile' in search box",
            candidateLogits = floatArrayOf(5.0f, 0.1f, 0.1f, 0.1f),
            plannedActionType = ActionType.TYPE,
            plannedText = "onnx mobile"
        )
        assertTrue(step1.success)
        assertEquals(RoutingTarget.SYSTEM_1_LAYA, step1.routingTarget)
        assertEquals("onnx mobile", webExt.getElementValue(searchInputId))
        assertEquals(MemoryState.UNLOADED, memorySm.state)

        // Step 2: High-confidence System 1 CLICK on search submit button (#2)
        val step2 = orchestrator.executeStep(
            sessionId = "tab-shop",
            goal = "Click Search button",
            candidateLogits = floatArrayOf(0.1f, 5.2f, 0.1f, 0.1f),
            plannedActionType = ActionType.CLICK
        )
        assertTrue(step2.success)
        assertEquals(RoutingTarget.SYSTEM_1_LAYA, step2.routingTarget)
        assertTrue(webExt.wasClicked(searchBtnId))
        assertEquals(0, session.reloadCount, "Submit button click must not trigger accidental page reload")

        // Step 3: Ambiguous sort goal -> low confidence triggers NOUL -> escalates to System 2 Local SLM
        val step3 = orchestrator.executeStep(
            sessionId = "tab-shop",
            goal = "Sort results by lowest price first",
            candidateLogits = floatArrayOf(1.0f, 1.0f, 1.1f, 0.9f), // max prob ~0.27 < 0.65 -> NOUL
            plannedActionType = ActionType.SELECT
        )
        assertTrue(step3.success)
        assertTrue(step3.noulTriggered)
        assertEquals(RoutingTarget.SYSTEM_2_LOCAL_SLM, step3.routingTarget)
        assertEquals("price_asc", webExt.getElementValue(sortSelectId))
        assertEquals(MemoryState.IDLE_COUNTDOWN, memorySm.state)
        assertEquals(60, memorySm.remainingTtl)

        // Simulate 15 seconds of inactivity
        repeat(15) { memorySm.processEvent(MemoryEvent.Tick1s) }
        assertEquals(45, memorySm.remainingTtl)

        // Step 4: Deliberate checkout task re-wakes System 2 from IDLE_COUNTDOWN, cancelling timer
        val step4 = orchestrator.executeStep(
            sessionId = "tab-shop",
            goal = "Verify cart items and proceed to checkout",
            candidateLogits = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f),
            isReflexCandidate = false
        )
        assertTrue(step4.success)
        assertEquals(RoutingTarget.SYSTEM_2_LOCAL_SLM, step4.routingTarget)
        assertTrue(webExt.wasClicked(checkoutBtnId))
        assertEquals(2, s2Invocations)
        assertEquals(MemoryState.IDLE_COUNTDOWN, memorySm.state)
        assertEquals(60, memorySm.remainingTtl)
        assertEquals(0, session.reloadCount)
    }
}
