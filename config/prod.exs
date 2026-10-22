import Config

# Production never runs unlogged: refuse to start without an audit log.
# The directory can be set at runtime with ACTOR_TX_AUDIT_DIR (config/runtime.exs).
config :actor_tx_spike,
  require_audit: true,
  # Back-pressure: at most this many transactions running at once, more are refused as
  # "overloaded". 100 is the knee measured with the demo providers (docs/quality/LOAD_RESULTS.md);
  # measure again against real providers. Override at run time with ACTOR_TX_MAX_IN_FLIGHT.
  admission: [max_in_flight: 100, retry_after_ms: 100],
  audit: [dir: "audit", logs: ["GLOBAL"]]
