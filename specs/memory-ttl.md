# Specification: System 2 Local SLM Inactivity Memory TTL (60s)

**Spec ID:** `SPEC-MEM-001`  
**Status:** VALIDATED  
**Component:** `corvus-android` (Orchestrator Resource Manager)

---

## 1. Formal State Machine Specification

On mobile hardware, keeping a 3B+ parameter local SLM (via `llama.cpp` JNI) persistently resident in RAM causes aggressive background process termination (LMK - Low Memory Killer).
Therefore, Corvus enforces a strict **60-second inactivity TTL** policy.

### 1.1 States
- `UNLOADED`: Model weights are not loaded in RAM. Memory footprint ~ 0 MB.
- `LOADING`: Model weights (`GGUF`) are being mapped into memory.
- `ACTIVE`: Model is currently performing inference or prompt evaluation.
- `IDLE_COUNTDOWN`: Model is resident in RAM, inactivity timer is running ($t \in [1, 60]$ seconds).
- `UNLOADING`: Releasing model context and freeing native buffers.

```
       +--------------+
       |   UNLOADED   |<-----------------------------------+
       +-------+------+                                    |
               |                                           |
               | Event: REQUEST_S2                         | Event: TTL_EXPIRED
               v                                           | (or LOW_MEMORY)
       +-------+------+                                    |
       |   LOADING    |                                    |
       +-------+------+                                    |
               |                                           |
               | Event: MODEL_READY                        |
               v                                           |
       +-------+------+         Event: REQUEST_S2          |
+----->|    ACTIVE    |<--------------------------------+  |
|      +-------+------+                                 |  |
|              |                                        |  |
|              | Event: INFERENCE_COMPLETE              |  |
|              v                                        |  |
|      +-------+------+                                 |  |
|      |IDLE_COUNTDOWN|                                 |  |
|      |  (TTL = 60s) |---------------------------------+  |
|      +-------+------+                                    |
|              |                                           |
|              | Event: TIMER_TICK (after 60 seconds)      |
|              v                                           |
|      +-------+------+                                    |
|      |  UNLOADING   |------------------------------------+
+------+       +------+
(cancel timer)
```

---

## 2. Transition Invariants

1. **State Transition on `REQUEST_S2`:**
   - If in `IDLE_COUNTDOWN`, immediately cancel countdown timer and transition to `ACTIVE`.
   - If in `UNLOADED`, transition to `LOADING`, allocate context, and then enter `ACTIVE`.
2. **State Transition on `INFERENCE_COMPLETE`:**
   - Transition to `IDLE_COUNTDOWN`.
   - Start 60-second monotonic countdown timer.
3. **State Transition on `TTL_EXPIRED` (Timer = 0):**
   - Call native `release_model()`.
   - Transition to `UNLOADED`.
4. **State Transition on `LOW_MEMORY_WARNING`:**
   - Unconditionally abort countdown and free memory immediately, transitioning to `UNLOADED`.
