package dev.corvus.browser.ui

import dev.corvus.browser.rpc.AgentStatus

enum class ControlCenterLayoutMode {
    MOBILE_VERTICAL_SPLIT,
    TABLET_SIDE_SHEET
}

enum class AgentBadgeIndicator {
    NONE,
    PULSING_BLUE_DOT,
    CYAN_PROGRESS_RING,
    AMBER_DOT,
    RED_DOT
}

data class ControlCenterUiState(
    val screenWidthDp: Int,
    val layoutMode: ControlCenterLayoutMode,
    val verticalSplitRatio: Float = 0.50f,
    val sideSheetWidthDp: Int = 380,
    val isSideSheetExpanded: Boolean = true,
    val agentStatus: AgentStatus = AgentStatus.IDLE,
    val badgeIndicator: AgentBadgeIndicator = AgentBadgeIndicator.NONE
)

/**
 * Responsive UI Control Center state controller implementing `SPEC-UI-001`.
 * - Mobile (< 600 dp): adjustable vertical split clamped to [0.30, 0.70].
 * - Tablet/Foldable (>= 600 dp): persistent 380 dp right side-sheet without tab reload.
 */
class ControlCenterStateController(screenWidthDp: Int = 412) {
    var state: ControlCenterUiState = ControlCenterUiState(
        screenWidthDp = screenWidthDp,
        layoutMode = resolveLayoutMode(screenWidthDp)
    )
        private set

    fun onScreenWidthChanged(newWidthDp: Int) {
        state = state.copy(
            screenWidthDp = newWidthDp,
            layoutMode = resolveLayoutMode(newWidthDp)
        )
    }

    fun updateVerticalSplit(requestedRatio: Float) {
        val clamped = requestedRatio.coerceIn(0.30f, 0.70f)
        state = state.copy(verticalSplitRatio = clamped)
    }

    fun toggleSideSheet(expanded: Boolean = !state.isSideSheetExpanded) {
        state = state.copy(isSideSheetExpanded = expanded)
    }

    fun onAgentStatusChanged(status: AgentStatus, awaitingUserConfirmation: Boolean = false) {
        val badge = when {
            awaitingUserConfirmation -> AgentBadgeIndicator.AMBER_DOT
            status == AgentStatus.THINKING_S1 || status == AgentStatus.EXECUTING -> AgentBadgeIndicator.PULSING_BLUE_DOT
            status == AgentStatus.THINKING_S2 || status == AgentStatus.OBSERVING -> AgentBadgeIndicator.CYAN_PROGRESS_RING
            status == AgentStatus.ERROR -> AgentBadgeIndicator.RED_DOT
            else -> AgentBadgeIndicator.NONE
        }
        state = state.copy(agentStatus = status, badgeIndicator = badge)
    }

    private fun resolveLayoutMode(widthDp: Int): ControlCenterLayoutMode {
        return if (widthDp < 600) {
            ControlCenterLayoutMode.MOBILE_VERTICAL_SPLIT
        } else {
            ControlCenterLayoutMode.TABLET_SIDE_SHEET
        }
    }
}
