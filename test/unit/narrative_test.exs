defmodule ActorTxSpike.NarrativeTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Events.Event
  alias ActorTxSpike.Narrative

  @at 1_700_000_000_123

  test "formats a line with time, transaction, actor and message" do
    e = %Event{
      seq: 1,
      at_ms: @at,
      tx_id: "tx-7",
      actor: {:provider, :alpha},
      kind: :received,
      message: "received pay"
    }

    line = Narrative.format(e)
    assert line =~ ~r/^\d\d:\d\d:\d\d\.123  tx-7 +provider:alpha +received pay$/
  end

  test "events without a transaction show a dash" do
    e = %Event{seq: 1, at_ms: @at, tx_id: nil, actor: :sentinel, kind: :probe, message: "probing"}
    assert Narrative.format(e) =~ ~r/ -  +sentinel +probing$/
  end

  test "colour is opt-in and varies by actor" do
    mk = fn actor ->
      %Event{seq: 1, at_ms: @at, tx_id: "t", actor: actor, kind: :k, message: "m"}
    end

    plain = Narrative.format(mk.(:payment))
    refute plain =~ "\e["

    for actor <- [:payment, :selector, :sentinel, {:provider, :x}, :operator] do
      assert Narrative.format(mk.(actor), color: true) =~ "\e["
    end

    assert Narrative.actor_label({:provider, :x}) == "provider:x"
    assert Narrative.actor_label(:payment) == "payment"
  end
end
