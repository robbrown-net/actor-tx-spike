import Config

# The audit log is part of the system, not an add-on. Production requires it
# (see config/prod.exs); development and tests may run without one.
config :actor_tx_spike, require_audit: false

import_config "#{config_env()}.exs"
