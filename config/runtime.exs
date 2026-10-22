import Config

# Where the audit logs live in production; the log names must cover every log the
# jurisdiction rules can name (ACTOR_TX_AUDIT_LOGS="GLOBAL,IN,CN").
if config_env() == :prod do
  logs = "ACTOR_TX_AUDIT_LOGS" |> System.get_env("GLOBAL") |> String.split(",", trim: true)

  config :actor_tx_spike,
    audit: [dir: System.get_env("ACTOR_TX_AUDIT_DIR", "audit"), logs: logs],
    admission: [
      max_in_flight: "ACTOR_TX_MAX_IN_FLIGHT" |> System.get_env("100") |> String.to_integer(),
      retry_after_ms: 100
    ]
end
