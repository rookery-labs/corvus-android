package dev.corvus.browser.routing

enum class NetworkState {
    ONLINE,
    OFFLINE
}

enum class RoutingTarget {
    SYSTEM_1_LAYA,
    SYSTEM_2_LOCAL_SLM,
    SYSTEM_2_CLOUD_BYOK
}

data class UserPreferences(
    val strictPrivacyMode: Boolean = false,
    val byokApiKey: String? = null
)

data class TaskRequest(
    val instruction: String,
    val tokenCount: Int,
    val isReflexCandidate: Boolean,
    val reflexConfidenceScore: Float = 0.0f
)

class DeterministicRoutingEngine(
    val maxLocalContextTokens: Int = 4096,
    val reflexConfidenceThreshold: Float = 0.65f
) {
    fun route(
        request: TaskRequest,
        network: NetworkState,
        preferences: UserPreferences
    ): RoutingTarget {
        // Level 1: Network / Offline Check
        if (network == NetworkState.OFFLINE) {
            return if (request.isReflexCandidate && request.reflexConfidenceScore >= reflexConfidenceThreshold) {
                RoutingTarget.SYSTEM_1_LAYA
            } else {
                RoutingTarget.SYSTEM_2_LOCAL_SLM
            }
        }

        // Level 2: Context Capacity Check
        val exceedsLocalContext = request.tokenCount > maxLocalContextTokens
        if (exceedsLocalContext) {
            if (preferences.strictPrivacyMode) {
                // Privacy forbids cloud, fallback to local SLM with truncated/sliding window
                return RoutingTarget.SYSTEM_2_LOCAL_SLM
            }
            return RoutingTarget.SYSTEM_2_CLOUD_BYOK
        }

        // Level 3: User Privacy Policy
        if (preferences.strictPrivacyMode) {
            return if (request.isReflexCandidate && request.reflexConfidenceScore >= reflexConfidenceThreshold) {
                RoutingTarget.SYSTEM_1_LAYA
            } else {
                RoutingTarget.SYSTEM_2_LOCAL_SLM
            }
        }

        // Level 4: Functional Task Complexity & Confidence
        if (request.isReflexCandidate && request.reflexConfidenceScore >= reflexConfidenceThreshold) {
            return RoutingTarget.SYSTEM_1_LAYA
        }

        // Deliberate Task
        return if (preferences.byokApiKey != null && preferences.byokApiKey.isNotBlank()) {
            RoutingTarget.SYSTEM_2_CLOUD_BYOK
        } else {
            RoutingTarget.SYSTEM_2_LOCAL_SLM
        }
    }
}
