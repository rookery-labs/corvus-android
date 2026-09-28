# Specification: Deterministic 4-Level Routing Cascade

**Spec ID:** `SPEC-ROUTE-001`  
**Status:** VALIDATED  
**Component:** `corvus-android` (Orchestrator Routing Engine)

---

## 1. Architectural Routing Flow

The Corvus Orchestrator routes incoming user tasks and browser observations through a deterministic 4-level waterfall:

```
[Input Observation & Goal]
           |
           v
  +-------------------------------------------------+
  | Level 1: Network / Offline Check                |
  | Is Network Available?                           |
  +------------------------+------------------------+
           | NO            | YES
           v               v
    [FORCE ON-DEVICE]   +---------------------------------------+
    (S1 or Local SLM)   | Level 2: Context Capacity Check       |
                        | Does Prompt exceed Local SLM Window?  |
                        +-------------------+-------------------+
                                 | YES      | NO
                                 v          v
                          [FORCE CLOUD]  +---------------------------------------+
                          (BYOK API)     | Level 3: User Privacy Policy          |
                                         | Is "Strict On-Device Only" enabled?   |
                                         +-------------------+-------------------+
                                                  | YES      | NO
                                                  v          v
                                           [LOCAL PATH]   +---------------------------------------+
                                                          | Level 4: Functional Task Complexity   |
                                                          | Task is Reflex vs Deliberate?         |
                                                          +-------------------+-------------------+
                                                                   | Reflex   | Deliberate
                                                                   v          v
                                                            [SYSTEM 1 LAYA]  [SYSTEM 2 SLM / CLOUD]
                                                            (< 40ms reflex)  (Deep reasoning)
```

---

## 2. Cascade Evaluation Rules

### Level 1: Network Connectivity
- If `networkState == OFFLINE`:
  - Routing strictly forbids Cloud endpoints.
  - Target must be `SYSTEM_1_LAYA` or `SYSTEM_2_LOCAL_SLM`.

### Level 2: Context Window Capacity
- If estimated token count $N_{\text{tokens}} > C_{\text{local\_slm}}$ (e.g. 4096 tokens):
  - Target is routed to `SYSTEM_2_CLOUD_BYOK` (or truncated/escalated).

### Level 3: User Privacy Preferences
- If `userPreferences.strictPrivacyMode == true`:
  - Cloud BYOK is strictly disallowed.
  - Target routes to `SYSTEM_1_LAYA` or `SYSTEM_2_LOCAL_SLM`.

### Level 4: Functional Typing & Complexity
- If action type is reflex (`CLICK`, `SIMPLE_INPUT`, `SCROLL`) and confidence score >= 0.65:
  - Route to `SYSTEM_1_LAYA` (< 40ms latency budget).
- If action requires multi-step planning, comparison, extraction, or if System 1 emitted `NOUL` (`noul == true`):
  - Route to `SYSTEM_2` (Local SLM or Cloud BYOK based on Level 2 & 3).
