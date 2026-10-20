defmodule ActorTxSpike.ApplicationTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Application, as: App
  alias ActorTxSpike.Audit.Router

  test "the audit log is required only when the configuration says so" do
    assert :ok = App.check([])
    assert :ok = App.check(require_audit: false)
    assert :ok = App.check(require_audit: true, audit: [dir: "audit"])
    assert {:error, :audit_required} = App.check(require_audit: true)
    assert {:error, :audit_required} = App.check(require_audit: true, audit: nil)
  end

  test "without an audit setting the tree has no router; with one, it sits right after the event stream" do
    plain = App.children([])
    assert hd(plain) == ActorTxSpike.Events
    refute Enum.any?(plain, &match?({Router, _}, &1))

    with_audit = App.children(audit: [dir: "audit", logs: ["GLOBAL", "IN"], junk: 1])
    assert [ActorTxSpike.Events, {Router, opts} | rest] = with_audit
    assert opts == [dir: "audit", logs: ["GLOBAL", "IN"]]
    assert ActorTxSpike.TransactionSupervisor in rest
    assert length(with_audit) == length(plain) + 1
  end

  test "the production configuration requires an audit log and names one" do
    {config, _} = Config.Reader.read_imports!("config/prod.exs", env: :prod)
    app = Keyword.fetch!(config, :actor_tx_spike)
    assert app[:require_audit]
    assert :ok = App.check(app)
    assert Keyword.has_key?(app, :audit)
  end
end
