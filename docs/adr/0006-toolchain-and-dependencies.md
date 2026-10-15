# ADR 0006: Toolchain and dependencies

Status: accepted · Date: 2026-10-15

## Context
The owner's older Intel MacBook is no longer supported by Homebrew.

## Decision
* OTP 27 and Elixir 1.18 live in a local, gitignored `.tooling/` directory; Hex is installed into `.tooling/mix` and `.tooling/hex`.
* **Runtime has zero third-party dependencies** (uses the Elixir 1.18 `JSON` module and OTP sockets).
* Analysis tools (`credo`, `sobelow`, `mix_audit`) are `only: [:dev, :test]`, `runtime: false`.
* Do not suggest `brew`.

## Consequences
* A fresh clone needs a toolchain; bootstrap instructions are in the README.
* The small attack surface and easy audit are deliberate; adding runtime deps is a decision, not a default.
