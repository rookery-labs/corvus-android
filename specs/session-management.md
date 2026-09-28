# Specification: GeckoSession Lifecycle Management & Isolation

**Spec ID:** `SPEC-SESSION-001`  
**Status:** VALIDATED  
**Component:** `corvus-android` (GeckoView Integration Layer)

---

## 1. Zero-Reload Detach/Reattach Protocol

In mobile browsing, UI configuration changes (screen rotation, multi-window split screen adjustments, tablet side-sheet toggle) traditionally trigger activity destruction or view detachment, causing web pages to reload and agent state to be lost.

Corvus decouples the `GeckoSession` instance from the `GeckoView` visual surface.

```
+-------------------------------------------------------------------+
|                        CorvusSessionManager                       |
|                                                                   |
|   +-----------------------+              +---------------------+  |
|   |     GeckoSession      |              |      GeckoView      |  |
|   |  (DOM, JS State, IPC) |              |   (Android Surface) |  |
|   +-----------+-----------+              +----------+----------+  |
|               |                                     |             |
|               |        releaseSession()             |             |
|               +<------------------------------------+             |
|               | (Visual detached, state retained)   |             |
|               |                                     |             |
|               |        setSession(existingSession)  |             |
|               +------------------------------------>+             |
|                 (Surface re-bound, NO F5 reload)                  |
+-------------------------------------------------------------------+
```

### 1.1 Invariants
1. `releaseSession()`:
   - Invoked when `GeckoView` is paused or detached from the window.
   - The underlying `GeckoSession` remains alive in memory.
   - WebExtension content scripts and WebSocket connections continue executing without interruption.
2. `setSession(session)`:
   - Invoked when the new or resized `GeckoView` is attached.
   - Rebinds the existing compositing surface.
   - Strictly suppresses any reload (`F5`) or navigation event.

---

## 2. Contextual Identities (Tab Isolation)

Each agent session runs in an isolated container context using Mozilla `Contextual Identities`:
- `contextId: "personal"` | `contextId: "work"` | `contextId: "banking"` | `contextId: "agent_isolated"`
- Cookies, localStorage, IndexedDB, and HTTP cache are strictly partitioned per container ID.
- The agent executor tab operates under an ephemeral `agent_isolated` container by default unless the user grants authenticated access to a personal container.
