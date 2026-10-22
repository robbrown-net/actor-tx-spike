defmodule ActorTxSpike.ApplicationTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Admission
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

  test "admission control is part of the tree and takes its limit from the configuration" do
    assert {Admission, []} in App.children([])
    assert {Admission, [max_in_flight: 50]} in App.children(admission: [max_in_flight: 50])

    # admission must be up before transactions can be submitted
    children = App.children([])

    assert Enum.find_index(children, &match?({Admission, _}, &1)) <
             Enum.find_index(children, &(&1 == ActorTxSpike.TransactionSupervisor))
  end

  test "production sets a back-pressure limit" do
    {config, _} = Config.Reader.read_imports!("config/prod.exs", env: :prod)
    admission = config |> Keyword.fetch!(:actor_tx_spike) |> Keyword.fetch!(:admission)
    assert is_integer(admission[:max_in_flight]) and admission[:max_in_flight] > 0
  end

  test "the production configuration requires an audit log and names one" do
    {config, _} = Config.Reader.read_imports!("config/prod.exs", env: :prod)
    app = Keyword.fetch!(config, :actor_tx_spike)
    assert app[:require_audit]
    assert :ok = App.check(app)
    assert Keyword.has_key?(app, :audit)
  end
end
