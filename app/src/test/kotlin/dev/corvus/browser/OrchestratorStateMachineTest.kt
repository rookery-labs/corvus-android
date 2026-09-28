package dev.corvus.browser

import dev.corvus.browser.orchestrator.MemoryEvent
import dev.corvus.browser.orchestrator.MemoryState
import dev.corvus.browser.orchestrator.MemoryTtlStateMachine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrchestratorStateMachineTest {

    @Test
    fun testInitialStateIsUnloaded() {
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 60)
        assertEquals(MemoryState.UNLOADED, sm.state)
        assertEquals(0, sm.remainingTtl)
    }

    @Test
    fun testTransitionFromUnloadedToActive() {
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 60)
        sm.processEvent(MemoryEvent.RequestS2)
        assertEquals(MemoryState.LOADING, sm.state)

        sm.processEvent(MemoryEvent.ModelReady)
        assertEquals(MemoryState.ACTIVE, sm.state)
    }

    @Test
    fun testInferenceCompleteStarts60sCountdown() {
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 60)
        sm.processEvent(MemoryEvent.RequestS2)
        sm.processEvent(MemoryEvent.ModelReady)
        assertEquals(MemoryState.ACTIVE, sm.state)

        sm.processEvent(MemoryEvent.InferenceComplete)
        assertEquals(MemoryState.IDLE_COUNTDOWN, sm.state)
        assertEquals(60, sm.remainingTtl)
    }

    @Test
    fun testTtlCountdownExpirationReleasesMemory() {
        var released = false
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 3, onModelRelease = { released = true })

        sm.processEvent(MemoryEvent.RequestS2)
        sm.processEvent(MemoryEvent.ModelReady)
        sm.processEvent(MemoryEvent.InferenceComplete)

        assertEquals(MemoryState.IDLE_COUNTDOWN, sm.state)
        assertEquals(3, sm.remainingTtl)

        sm.processEvent(MemoryEvent.Tick1s) // remaining 2
        assertEquals(2, sm.remainingTtl)
        assertEquals(MemoryState.IDLE_COUNTDOWN, sm.state)
        assertFalse(released)

        sm.processEvent(MemoryEvent.Tick1s) // remaining 1
        assertEquals(1, sm.remainingTtl)
        assertFalse(released)

        sm.processEvent(MemoryEvent.Tick1s) // remaining 0 -> triggers TtlExpired
        assertEquals(MemoryState.UNLOADED, sm.state)
        assertEquals(0, sm.remainingTtl)
        assertTrue(released)
    }

    @Test
    fun testNewRequestDuringCountdownResetsToActive() {
        var released = false
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 60, onModelRelease = { released = true })

        sm.processEvent(MemoryEvent.RequestS2)
        sm.processEvent(MemoryEvent.ModelReady)
        sm.processEvent(MemoryEvent.InferenceComplete)

        sm.processEvent(MemoryEvent.Tick1s) // 59s
        assertEquals(59, sm.remainingTtl)

        // New request arrives before expiration
        sm.processEvent(MemoryEvent.RequestS2)
        assertEquals(MemoryState.ACTIVE, sm.state)
        assertEquals(0, sm.remainingTtl)
        assertFalse(released)
    }

    @Test
    fun testLowMemoryImmediatelyFreesResources() {
        var released = false
        val sm = MemoryTtlStateMachine(defaultTtlSeconds = 60, onModelRelease = { released = true })

        sm.processEvent(MemoryEvent.RequestS2)
        sm.processEvent(MemoryEvent.ModelReady)
        sm.processEvent(MemoryEvent.InferenceComplete)

        sm.processEvent(MemoryEvent.LowMemory)
        assertEquals(MemoryState.UNLOADED, sm.state)
        assertTrue(released)
    }
}
