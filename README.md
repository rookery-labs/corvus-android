# Corvus Android — Autonomous Agentic Mobile Browser

The core Android mobile browser application of the **Corvus** ecosystem, built as a fork of Mozilla Fenix powered by GeckoView, Jetpack Compose, ONNX Runtime Mobile, and local SLM inference.

## Architecture & Core Modules

- **Orchestrator & Memory TTL (`dev.corvus.browser.orchestrator`):** Manages cognitive state and enforces a strict 60-second inactivity TTL to release heavy SLM weights (`llama.cpp`) from RAM.
- **Deterministic 4-Level Routing Cascade (`dev.corvus.browser.routing`):**
  1. *Level 1:* Network / Offline policy (strictly forbids Cloud when offline).
  2. *Level 2:* Context window capacity (escalates to Cloud BYOK when prompt exceeds local window).
  3. *Level 3:* User privacy policy (enforces on-device processing when strict privacy is enabled).
  4. *Level 4:* Functional typing (dispatches reflex tasks < 40ms to System 1 Laya, and deliberate multi-step tasks to System 2).
- **Session Management (`dev.corvus.browser.session`):** Zero-reload `GeckoSession` detach/reattach across Android configuration and split-screen changes, with strict Contextual Identities isolation.
- **IPC & JSON-RPC (`dev.corvus.browser.rpc`):** Type-safe bi-directional messaging with the built-in WebExtension.

## Specifications (SDD)

- [GeckoSession Lifecycle & Isolation](specs/session-management.md)
- [4-Level Deterministic Routing Cascade](specs/routing-cascade.md)
- [Responsive UI Ergonomics & Control Center](specs/ui-control-center.md)
- [System 2 Inactivity Memory TTL (60s)](specs/memory-ttl.md)

## Development & Testing (TDD)

```bash
# Execute unit and contract test suite
./gradlew test
```

## License

Licensed under the [Apache License 2.0](LICENSE).
