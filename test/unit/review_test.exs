defmodule ActorTxSpike.ReviewTest do
  @moduledoc """
  The review workflow against real transactions that really ended in `needs_review`: the
  settlement the provider refused. The queue is one named process, so these tests run alone.
  """
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Events, Payment, Policy, Review, Wait}
  alias ActorTxSpike.Provider.{Fake, Server}
  alias ActorTxSpike.Review.Queue

  @fast [
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    step_timeout_ms: 500,
    deadline_ms: 1_000,
    linger_ms: 200
  ]

  setup do
    {:ok, flag} = Agent.start_link(fn -> :ok end)
    {:ok, fake} = Fake.start_link(id: :best, rate: 1.1, settle_mode: :reject)

    {:ok, server} =
      Server.start_link(adapter: Fake, config: fake, id: :best, health: [failure_threshold: 50])

    providers = [%{id: :best, server: server}]

    start_supervised!(
      {Review, providers: fn -> providers end, barrier: fn -> Agent.get(flag, & &1) end}
    )

    {:ok, fake: fake, providers: providers, flag: flag}
  end

  defp audit_down(flag), do: Agent.update(flag, fn _ -> {:error, :disk_full} end)

  # A transaction whose settlement the provider refuses: paid, not settled, escalated.
  defp needs_review_tx(providers) do
    id = "rv#{System.unique_integer([:positive])}"
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})

    {:ok, ^id} =
      ActorTxSpike.submit(%{id: id, amount: 100},
        providers: providers,
        policy: policy,
        opts: @fast
      )

    assert {:error, {:needs_review, _}} = Payment.await(id)
    Wait.until!(fn -> Enum.any?(Review.queue(), &(&1.tx == id)) end)
    id
  end

  defp entry(id), do: Enum.find(Review.queue(), &(&1.tx == id))

  test "a transaction that needs review appears in the queue with what is known about it", %{
    providers: p
  } do
    id = needs_review_tx(p)

    assert %{
             status: :open,
             provider: "best",
             done: ["accept_quote", "authorise", "pay"],
             reason: reason,
             story: story,
             resolution: nil,
             actions: []
           } = entry(id)

    assert reason =~ "settle"
    assert Enum.any?(story, &(&1 =~ "best did pay"))
    assert %{available: true, open: open, entries: entries} = Review.snapshot()
    assert open >= 1 and Enum.any?(entries, &(&1.tx == id))
  end

  test "checking asks the provider about each step and records it, changing nothing", %{
    providers: p,
    fake: fake
  } do
    id = needs_review_tx(p)
    Events.subscribe()
    before = Fake.effects(fake)

    assert {:ok, answers} = Review.check(id, "robin")
    assert [%{op: "accept_quote", key: key} | _] = answers
    assert key == "#{id}:best:accept"
    assert Enum.map(answers, & &1.op) == ~w(accept_quote authorise pay settle)
    assert Enum.find(answers, &(&1.op == "pay")).answer =~ ":ok"
    assert Fake.effects(fake) == before

    assert_receive {:tx_event,
                    %{kind: :review_checked, tx_id: ^id, actor: :review, data: %{by: "robin"}}}

    assert [%{kind: "check", by: "robin"}] = entry(id).actions
  end

  test "a step that is safe to repeat can be sent again under its original key, and is recorded first",
       %{providers: p, fake: fake} do
    id = needs_review_tx(p)
    Events.subscribe()
    assert Fake.effect_count(fake, :settle) == 0

    # the provider's refusal was the problem; it is fixed, so settle goes through
    Fake.configure(fake, settle_mode: :ok)
    assert {:ok, %{op: "settle", key: key, result: result}} = Review.retry(id, "settle", "robin")
    assert key == "#{id}:best:settle" and result =~ ":ok"
    assert Fake.effect_count(fake, :settle) == 1

    # intent is logged before the result
    assert_receive {:tx_event, %{kind: :review_retry, data: %{op: "settle", by: "robin"}}}
    assert_receive {:tx_event, %{kind: :review_result, data: %{result: ^result}}}

    # again: the same key, so the provider does nothing more
    assert {:ok, _} = Review.retry(id, "settle", "robin")
    assert Fake.effect_count(fake, :settle) == 1

    assert Enum.map(entry(id).actions, & &1.kind) == ~w(retry result retry result)
  end

  test "undo operations are keyed by the step they undo; other steps cannot be sent again", %{
    providers: p,
    fake: fake
  } do
    id = needs_review_tx(p)

    assert {:ok, %{key: key}} = Review.retry(id, "refund", "robin")
    assert key == "#{id}:best:pay"
    assert Fake.effect_count(fake, :refund) == 1

    assert {:error, message} = Review.retry(id, "pay", "robin")
    assert message =~ "only these can be sent again"
    assert Fake.effect_count(fake, :pay) == 1
  end

  test "resolving needs a known outcome, who is deciding, and why; then the review is closed", %{
    providers: p
  } do
    id = needs_review_tx(p)

    assert {:error, "choose one of: paid, not_paid, written_off"} =
             Review.resolve(id, "maybe", "robin", "x")

    assert {:error, "say who is deciding"} = Review.resolve(id, "paid", "", "x")
    assert {:error, "say why" <> _} = Review.resolve(id, "paid", "robin", "  ")
    assert %{status: :open} = entry(id)

    Events.subscribe()

    assert {:ok, %{outcome: "paid", by: "robin", note: "confirmed with the provider's statement"}} =
             Review.resolve(id, "paid", "robin", "confirmed with the provider's statement")

    assert_receive {:tx_event,
                    %{kind: :review_resolved, tx_id: ^id, data: %{outcome: "paid", by: "robin"}}}

    assert %{status: :resolved, resolution: %{outcome: "paid"}} = entry(id)

    assert {:error, "#{id} has already been resolved"} ==
             Review.resolve(id, "paid", "robin", "again")

    assert {:error, _} = Review.check(id, "robin")
    assert {:error, "nope is not waiting for review"} = Review.check("nope", "robin")
  end

  test "if the audit log cannot take the record, nothing is sent and nothing changes", %{
    providers: p,
    fake: fake,
    flag: flag
  } do
    id = needs_review_tx(p)
    audit_down(flag)
    Fake.configure(fake, settle_mode: :ok)

    assert {:error, "the audit log is unavailable, so nothing was done"} =
             Review.retry(id, "settle", "robin")

    assert Fake.effect_count(fake, :settle) == 0
    assert {:error, _} = Review.check(id, "robin")
    assert {:error, _} = Review.resolve(id, "paid", "robin", "because")
    assert %{status: :open, actions: []} = entry(id)
  end

  test "every action needs a name, an open entry, and a provider that is there", %{providers: p} do
    id = needs_review_tx(p)
    assert {:error, "say who is acting" <> _} = Review.check(id, "")
    assert {:error, "say who is acting" <> _} = Review.retry(id, "settle", nil)
    assert {:error, "nope is not waiting for review"} = Review.check("nope", "robin")
  end

  test "a provider that is no longer there, or never was chosen, is said plainly" do
    stop_supervised!(Review)
    start_supervised!({Review, providers: fn -> [] end, barrier: fn -> :ok end})

    records = [
      rec("t-gone", 1, "tx_received", %{"request" => %{"amount" => 100}}),
      rec("t-gone", 2, "provider_chosen", %{"provider" => "best", "remaining" => []}),
      rec("t-gone", 3, "needs_review", %{}, "needs review"),
      rec("t-none", 1, "tx_received", %{"request" => %{"amount" => 100}}),
      rec("t-none", 3, "needs_review", %{}, "needs review")
    ]

    assert :ok = Review.load(records)
    assert {:error, "provider best is not available"} = Review.check("t-gone", "robin")

    assert {:error, "no provider was chosen for this transaction"} =
             Review.check("t-none", "robin")
  end

  describe "rebuilding from the audit log" do
    test "the queue and what people did come back from the records, and live entries win", %{
      providers: p
    } do
      live = needs_review_tx(p)

      records = [
        rec("t-old", 1, "tx_received", %{"request" => %{"amount" => 5_000}}),
        rec("t-old", 2, "provider_chosen", %{"provider" => "best", "remaining" => []}),
        rec("t-old", 3, "step_succeeded", %{"provider" => "best", "op" => "accept_quote"}),
        rec("t-old", 4, "needs_review", %{}, "NEEDS REVIEW: could not settle"),
        rec("t-old", 5, "review_checked", %{"by" => "robin", "answers" => []}, "r", "review"),
        rec("t-old", 6, "review_retry", %{"by" => "robin", "op" => "settle"}, "r", "review"),
        rec(
          "t-old",
          7,
          "review_result",
          %{"by" => "robin", "op" => "settle", "result" => ":ok"},
          "r",
          "review"
        ),
        rec(
          "t-old",
          8,
          "review_resolved",
          %{"by" => "robin", "outcome" => "paid", "note" => "confirmed"},
          "r",
          "review"
        ),
        rec("t-open", 1, "tx_received", %{"request" => %{"amount" => 100}}),
        rec("t-open", 2, "needs_review", %{}, "NEEDS REVIEW: x"),
        # a live entry's id also in the log: the live one is kept
        rec(live, 1, "tx_received", %{"request" => %{"amount" => 1}}),
        rec(live, 2, "needs_review", %{}, "from the log")
      ]

      assert :ok = Review.load(records)

      assert %{status: :resolved, resolution: %{outcome: "paid", by: "robin", note: "confirmed"}} =
               entry("t-old")

      assert Enum.map(entry("t-old").actions, & &1.kind) == ~w(check retry result)

      assert entry("t-old").done == ["accept_quote"] and
               entry("t-old").request == %{"amount" => 5_000}

      assert %{status: :open} = entry("t-open")
      assert entry(live).reason =~ "settle"

      # open ones come first
      queue = Review.queue()

      assert Enum.find_index(queue, &(&1.tx == "t-open")) <
               Enum.find_index(queue, &(&1.tx == "t-old"))
    end
  end

  test "dashboard commands are routed, and a bad one is explained", %{providers: p} do
    id = needs_review_tx(p)
    assert {:ok, [_ | _]} = Review.command(%{"do" => "check", "tx" => id, "by" => "robin"})

    assert {:ok, %{op: "refund"}} =
             Review.command(%{"do" => "retry", "tx" => id, "op" => "refund", "by" => "robin"})

    assert {:ok, %{outcome: "not_paid"}} =
             Review.command(%{
               "do" => "resolve",
               "tx" => id,
               "outcome" => "not_paid",
               "by" => "robin",
               "note" => "refunded"
             })

    assert {:error, "a review command needs do, tx and who is acting (check, retry, resolve)"} =
             Review.command(%{"do" => "dance"})
  end

  defp rec(tx, n, kind, data, message \\ nil, actor \\ "payment") do
    %{
      "n" => n,
      "seq" => n,
      "at" => n * 100,
      "tx" => tx,
      "actor" => actor,
      "kind" => kind,
      "message" => message,
      "data" => data
    }
  end

  test "an entry can be opened from a live event even when the transaction process is gone, and noise is ignored" do
    send(Review, :noise)

    send(
      Review,
      {:tx_event,
       %Events.Event{
         kind: :needs_review,
         actor: :payment,
         tx_id: "ghost-1",
         message: "gone",
         at_ms: 7,
         data: %{}
       }}
    )

    Wait.until!(fn -> Enum.any?(Review.queue(), &(&1.tx == "ghost-1")) end)
    assert %{status: :open, provider: nil, done: [], story: [], reason: "gone"} = entry("ghost-1")

    # hearing about it again changes nothing
    send(
      Review,
      {:tx_event,
       %Events.Event{
         kind: :needs_review,
         actor: :payment,
         tx_id: "ghost-1",
         message: "again",
         at_ms: 9,
         data: %{}
       }}
    )

    Process.sleep(20)
    assert Enum.count(Review.queue(), &(&1.tx == "ghost-1")) == 1
    assert entry("ghost-1").reason == "gone"
  end

  test "resolution needs a name even when none is given at all" do
    assert {:error, "say who is deciding"} =
             Queue.check_resolution("paid", nil, "why")

    assert Queue.outcomes() == ~w(paid not_paid written_off)
  end

  test "started without options it finds no providers and uses the real audit barrier" do
    stop_supervised!(Review)
    start_supervised!(Review)

    records = [
      rec("t-x", 1, "tx_received", %{"request" => %{"amount" => 1}}),
      rec("t-x", 2, "provider_chosen", %{"provider" => "best", "remaining" => []}),
      rec("t-x", 3, "needs_review", %{}, "needs review")
    ]

    :ok = Review.load(records)
    assert {:error, "provider best is not available"} = Review.check("t-x", "robin")
  end
end
