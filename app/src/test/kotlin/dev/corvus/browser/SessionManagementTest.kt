package dev.corvus.browser

import dev.corvus.browser.session.ContextualIdentity
import dev.corvus.browser.session.CorvusSessionManager
import dev.corvus.browser.session.MockGeckoSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionManagementTest {

    @Test
    fun testZeroReloadDetachAndReattach() {
        val manager = CorvusSessionManager()
        val session = manager.createSession("tab-1", "https://corvus.dev") as MockGeckoSession

        assertTrue(session.isAttached)
        assertEquals(0, session.reloadCount)

        // Detach visual surface (e.g. split screen resizing or configuration change)
        val detached = manager.releaseSession("tab-1")
        assertTrue(detached)
        assertFalse(session.isAttached)
        assertEquals(0, session.reloadCount)

        // Reattach to new surface
        val reattached = manager.setSession("tab-1")
        assertTrue(reattached)
        assertTrue(session.isAttached)
        // Strictly verify NO reload occurred
        assertEquals(0, session.reloadCount)
    }

    @Test
    fun testContextualIdentityIsolation() {
        val manager = CorvusSessionManager()
        val agentIdentity = ContextualIdentity(
            id = "agent_isolated",
            name = "Isolated Sandbox",
            color = "#00AAFF",
            isAgentIsolated = true
        )

        val session = manager.createSession("tab-2", "https://corvus.dev", agentIdentity)
        assertTrue(session.identity.isAgentIsolated)
        assertEquals("agent_isolated", session.identity.id)
    }
}
