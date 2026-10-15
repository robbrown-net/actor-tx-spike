# ADR 0004: Observability is a first-class requirement

Status: accepted · Date: 2026-10-15

## Context
The owner reported the system felt like a black box and wants to *see and feel* it run:
transactions unfolding, services receiving, processing and responding.

## Decision
* Every component announces what it does through one stream, `ActorTxSpike.Events`.
  Each event has a human `message` and structured `data`.
* The terminal narrative and the dashboard are **projections of that single stream**;
  neither has private knowledge.
* Providers (fakes now, real adapters later) narrate their own side: received, processing, responded.
* Emission is **fire-and-forget** with a bounded buffer: observing must never slow or break processing.
* The dashboard is also a **control surface** for the demo world so behaviour can be provoked.
* The dashboard server is dependency-free (OTP sockets, SSE) and loopback-only.

## Consequences
* Narrative is not durable yet; persistence belongs to the Phase 3 log.
* Resource and performance metrics are a separate design ([07](../architecture/07-observability-and-performance.md)).
* Control endpoints are unauthenticated: acceptable only because of loopback binding.

## Dashboard motion principles (added 2026-10-15)

Motion explains *what just changed*; it never decorates. Contributed from the owner's collaborator notes.

* One easing curve (`cubic-bezier(.2,.8,.2,1)`), short durations (0.15 / 0.3 s; 0.8 s for reveals), small distances (5–8 px).
* **Persistent DOM:** provider cards and transaction cards are created once and patched in place, so CSS transitions fire. Never rebuild with `innerHTML` on every event.
* The story pane is append-only; new events rise in, history replayed on connect does not animate.
* Meaningful cues only: provider state change flashes its card in the state colour; a finished transaction flashes green / red / amber; one pulsing "live" indicator.
* **Reduced motion is honoured** (`prefers-reduced-motion`) and there is a "Motion: on/off" switch, remembered per browser.
* Errors surface as a toast with the server message, not a blocking dialog.
