import Config

config :actor_tx_spike,
  store_path: Path.join(System.tmp_dir!(), "actor_tx_spike_test.bin"),
  subsystem_delay_ms: 0

config :logger, level: :warning
