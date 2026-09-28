package dev.corvus.browser.session

data class ContextualIdentity(
    val id: String,
    val name: String,
    val color: String,
    val isAgentIsolated: Boolean = false
)

interface GeckoSessionHolder {
    val sessionId: String
    val currentUrl: String
    val isAttached: Boolean
    val identity: ContextualIdentity
    fun releaseView(): Boolean
    fun attachView(): Boolean
}

class MockGeckoSession(
    override val sessionId: String,
    override var currentUrl: String,
    override val identity: ContextualIdentity
) : GeckoSessionHolder {
    override var isAttached: Boolean = true
        private set

    var reloadCount: Int = 0
        private set

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
