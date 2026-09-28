# Specification: UI Ergonomics & Agent Control Center

**Spec ID:** `SPEC-UI-001`  
**Status:** VALIDATED  
**Component:** `corvus-android` (Jetpack Compose UI Layer)

---

## 1. Responsive Screen Adaptive Layouts

### 1.1 Mobile Form Factors (< 600 dp Width)
- **Adjustable Vertical Split:**
  - The upper pane hosts the interactive `GeckoView` surface.
  - The lower pane hosts the Corvus Agent Assistant (streaming thought traces, plan steps, manual pause/resume buttons).
  - A draggable horizontal divider enables the user to allocate 30% to 70% of screen height to either component.

### 1.2 Tablet and Foldable Form Factors (>= 600 dp Width)
- **Persistent Side-Sheet:**
  - `GeckoView` occupies the full main viewport.
  - A persistent side-sheet (width = 380 dp) is docked to the right edge.
  - Toggling or collapsing the side-sheet updates layout coordinates without triggering a tab reload or resetting DOM state.

---

## 2. Non-Intrusive Control Center Action Button

- The primary browser toolbar features a dedicated **Corvus Agent Action Button**.
- **Status Indicators (Non-intrusive Dot):**
  - **Idle:** Neutral icon without badge.
  - **System 1 Reflex Executing:** Solid blue pulsing dot (4 dp).
  - **System 2 Deliberate Reasoning:** Solid cyan dot with circular progress ring.
  - **Awaiting User Confirmation:** Amber dot.
  - **Error / Intervention Required:** Red dot.
