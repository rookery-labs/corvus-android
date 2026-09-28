package dev.corvus.browser

import dev.corvus.browser.routing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutingEngineTest {
    private val engine = DeterministicRoutingEngine(
        maxLocalContextTokens = 4096,
        reflexConfidenceThreshold = 0.65f
    )

    @Test
    fun testLevel1OfflineForbidsCloud() {
        val request = TaskRequest(
            instruction = "Complex planning across tabs",
            tokenCount = 8000,
            isReflexCandidate = false
        )
        val prefs = UserPreferences(strictPrivacyMode = false, byokApiKey = "sk-test")

        val target = engine.route(request, NetworkState.OFFLINE, prefs)
        // Must fallback to local SLM because network is OFFLINE
        assertEquals(RoutingTarget.SYSTEM_2_LOCAL_SLM, target)
    }

    @Test
    fun testLevel1OfflineAllowsReflexSystem1() {
        val request = TaskRequest(
            instruction = "Click Submit",
            tokenCount = 50,
            isReflexCandidate = true,
            reflexConfidenceScore = 0.92f
        )
        val prefs = UserPreferences()

        val target = engine.route(request, NetworkState.OFFLINE, prefs)
        assertEquals(RoutingTarget.SYSTEM_1_LAYA, target)
    }

    @Test
    fun testLevel2ContextCapacityExceededRoutesToCloud() {
        val request = TaskRequest(
            instruction = "Summarize entire DOM trace with history",
            tokenCount = 6000,
            isReflexCandidate = false
        )
        val prefs = UserPreferences(strictPrivacyMode = false, byokApiKey = "sk-cloud")

        val target = engine.route(request, NetworkState.ONLINE, prefs)
        assertEquals(RoutingTarget.SYSTEM_2_CLOUD_BYOK, target)
    }

    @Test
    fun testLevel3StrictPrivacyEnforcesOnDeviceEvenForLargeContext() {
        val request = TaskRequest(
            instruction = "Process banking statement",
            tokenCount = 7000,
            isReflexCandidate = false
        )
        val prefs = UserPreferences(strictPrivacyMode = true, byokApiKey = "sk-cloud")

        val target = engine.route(request, NetworkState.ONLINE, prefs)
        assertEquals(RoutingTarget.SYSTEM_2_LOCAL_SLM, target)
    }

    @Test
    fun testLevel4ReflexCandidateWithHighConfidenceRoutesToSystem1() {
        val request = TaskRequest(
            instruction = "Click the target link",
            tokenCount = 120,
            isReflexCandidate = true,
            reflexConfidenceScore = 0.88f
        )
        val prefs = UserPreferences()

        val target = engine.route(request, NetworkState.ONLINE, prefs)
        assertEquals(RoutingTarget.SYSTEM_1_LAYA, target)
    }

    @Test
    fun testLevel4ReflexCandidateWithLowConfidenceEscalatesToSystem2() {
        val request = TaskRequest(
            instruction = "Click ambiguous button",
            tokenCount = 120,
            isReflexCandidate = true,
            reflexConfidenceScore = 0.45f // below threshold 0.65 -> NOUL
        )
        val prefs = UserPreferences(byokApiKey = "sk-test")

        val target = engine.route(request, NetworkState.ONLINE, prefs)
        assertEquals(RoutingTarget.SYSTEM_2_CLOUD_BYOK, target)
    }
}
