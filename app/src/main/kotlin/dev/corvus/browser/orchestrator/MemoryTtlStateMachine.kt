package dev.corvus.browser.orchestrator

enum class MemoryState {
    UNLOADED,
    LOADING,
    ACTIVE,
    IDLE_COUNTDOWN,
    UNLOADING
}

sealed class MemoryEvent {
    object RequestS2 : MemoryEvent()
    object ModelReady : MemoryEvent()
    object InferenceComplete : MemoryEvent()
    object Tick1s : MemoryEvent()
    object TtlExpired : MemoryEvent()
    object LowMemory : MemoryEvent()
}

class MemoryTtlStateMachine(
    val defaultTtlSeconds: Int = 60,
    private val onModelRelease: () -> Unit = {}
) {
    var state: MemoryState = MemoryState.UNLOADED
        private set

    var remainingTtl: Int = 0
        private set

    fun processEvent(event: MemoryEvent) {
        when (state) {
            MemoryState.UNLOADED -> {
                if (event is MemoryEvent.RequestS2) {
                    state = MemoryState.LOADING
                }
            }
            MemoryState.LOADING -> {
                if (event is MemoryEvent.ModelReady) {
                    state = MemoryState.ACTIVE
                } else if (event is MemoryEvent.LowMemory) {
                    releaseMemory()
                }
            }
            MemoryState.ACTIVE -> {
                if (event is MemoryEvent.InferenceComplete) {
                    state = MemoryState.IDLE_COUNTDOWN
                    remainingTtl = defaultTtlSeconds
                } else if (event is MemoryEvent.LowMemory) {
                    releaseMemory()
                }
            }
            MemoryState.IDLE_COUNTDOWN -> {
                when (event) {
                    is MemoryEvent.RequestS2 -> {
                        remainingTtl = 0
                        state = MemoryState.ACTIVE
                    }
                    is MemoryEvent.Tick1s -> {
                        remainingTtl--
                        if (remainingTtl <= 0) {
                            processEvent(MemoryEvent.TtlExpired)
                        }
                    }
                    is MemoryEvent.TtlExpired -> {
                        releaseMemory()
                    }
                    is MemoryEvent.LowMemory -> {
                        releaseMemory()
                    }
                    else -> {}
                }
            }
            MemoryState.UNLOADING -> {
                // Transitional state completing release
                state = MemoryState.UNLOADED
            }
        }
    }

    private fun releaseMemory() {
        state = MemoryState.UNLOADING
        onModelRelease()
        remainingTtl = 0
        state = MemoryState.UNLOADED
    }
}
