# Corvus Android — Agent Instructions & Domain Constraints

**Designated Primary Subagent:** `android_geckoview_dev`  
**Quality Assurance Auditor:** `qa_sdd_validator`

---

## Technical Domain

This repository encapsulates the core Android application for Corvus (a specialized fork of Mozilla Fenix) built on GeckoView, Jetpack Compose, ONNX Runtime Mobile, and local SLM inference.

## Key Invariants & Rules

1. **Zero-Reload GeckoSession Lifecycle (`specs/session-management.md`):**
   - Visual view detaches (`releaseSession()`) and rebinds (`setSession()`) must preserve the underlying session, running JavaScript state, and DOM connections without triggering F5 page reloads.
   - Contextual Identities must isolate cookies, cache, and storage per container (`agent_isolated` sandbox by default).
2. **4-Level Deterministic Routing Cascade (`specs/routing-cascade.md`):**
   - Level 1: OFFLINE state strictly forbids Cloud BYOK.
   - Level 2: Context token count > 4096 escalates to Cloud (if privacy mode allows).
   - Level 3: Strict privacy mode enforces on-device execution.
   - Level 4: Reflex candidate with confidence >= 0.65 routes to System 1 Laya (< 40ms); complex tasks route to System 2.
3. **Local SLM Inactivity TTL (`specs/memory-ttl.md`):**
   - Enforce 60-second countdown in `MemoryTtlStateMachine`.
   - Release native weights immediately on `LowMemory` signals or when the 60s countdown hits zero.
4. **Testing Protocol:**
   - Execute `./gradlew test --no-daemon` before every commit.
