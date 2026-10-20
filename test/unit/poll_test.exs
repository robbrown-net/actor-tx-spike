defmodule ActorTxSpike.PollTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Poll

  test "returns true as soon as the condition holds" do
    assert Poll.until(fn -> :yes end)
  end

  test "keeps asking until the condition holds" do
    counter = :counters.new(1, [])

    assert Poll.until(
             fn ->
               :counters.add(counter, 1, 1)
               :counters.get(counter, 1) >= 3
             end,
             every_ms: 1
           )

    assert :counters.get(counter, 1) == 3
  end

  test "gives up after the given number of tries" do
    counter = :counters.new(1, [])

    refute Poll.until(
             fn ->
               :counters.add(counter, 1, 1)
               false
             end,
             tries: 4,
             every_ms: 1
           )

    assert :counters.get(counter, 1) == 5
  end

  test "nil counts as not yet" do
    refute Poll.until(fn -> nil end, tries: 1, every_ms: 1)
  end
end
