import Config

config :actor_tx_spike,
  store_path: Path.expand("persist/transactions.bin", File.cwd!()),
  subsystem_delay_ms: 0

import_config "#{config_env()}.exs"
