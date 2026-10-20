import Config

# Production never runs unlogged: refuse to start without an audit log.
# The directory can be set at runtime with ACTOR_TX_AUDIT_DIR (config/runtime.exs).
config :actor_tx_spike,
  require_audit: true,
  audit: [dir: "audit", logs: ["GLOBAL"]]
