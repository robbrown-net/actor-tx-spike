defmodule ActorTxSpike.CurrencyIntegrationTest do
  @moduledoc """
  Currencies drive pricing, provider choice and eligibility in the demo world: a pair is priced
  from the market rate, only providers that carry both currencies are asked, and a region can
  forbid a currency.
  """
  use ExUnit.Case, async: false

  alias ActorTxSpike.Demo

  @fast [
    deadline_ms: 600,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    linger_ms: 60_000
  ]

  setup do
    prefix = "c#{System.unique_integer([:positive])}"
    {:ok, demo} = Demo.start_link(timers: false, payment_opts: @fast, prefix: prefix)
    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})
    on_exit(fn -> stop_quietly(demo) end)
    :ok
  end

  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end

  defp submit(extra) do
    {:ok, id} = Demo.control(Map.merge(%{"action" => "submit", "amount" => 100}, extra))
    {id, ActorTxSpike.await(id, 10_000)}
  end

  defp messages(id),
    do: id |> ActorTxSpike.snapshot() |> Map.fetch!(:history) |> Enum.map(&elem(&1, 3))

  test "a pair is priced from the market: dollars into rupees quote near 83, not near 1.08" do
    {id, result} = submit(%{"source_currency" => "USD", "destination_currency" => "INR"})
    assert {:ok, %{provider: provider, rate: rate}} = result

    # market USD/INR is 1.0 / 0.012 = 83.33; the provider's own spread moves it by well under 1%
    assert rate > 82 * 100_000_000 and rate < 85 * 100_000_000
    assert provider in [:northwind, :cobalt]
    assert Enum.any?(messages(id), &(&1 =~ "(USD to INR)"))
  end

  test "only providers that carry both currencies are asked, and the others are reported" do
    ActorTxSpike.Events.subscribe()

    {id, {:ok, %{provider: provider}}} =
      submit(%{"source_currency" => "USD", "destination_currency" => "SAR"})

    # SAR is carried by cobalt alone
    assert provider == :cobalt

    for other <- ~w(northwind bluebird meridian zenith) do
      expected = "not calling #{other}: it does not carry USD to SAR"

      assert_receive {:tx_event, %{tx_id: ^id, kind: :provider_skipped, message: ^expected}},
                     2_000
    end
  end

  test "a pair no provider carries has no quote" do
    # cobalt carries TRY, bluebird carries KRW, nobody carries both
    {_id, result} = submit(%{"source_currency" => "TRY", "destination_currency" => "KRW"})
    assert {:error, :no_quote} = result
  end

  test "a region can forbid a currency: INR cannot be used in China" do
    {_id, result} =
      submit(%{
        "source_currency" => "INR",
        "destination_currency" => "USD",
        "region" => "CN",
        "instrument" => "spot"
      })

    assert {:error, {:not_permitted, reasons}} = result
    assert "INR cannot be used in CN" in reasons

    # the same corridor with a currency China allows goes through meridian, which carries it
    {_id, ok} =
      submit(%{
        "source_currency" => "USD",
        "destination_currency" => "CNY",
        "region" => "CN",
        "instrument" => "spot"
      })

    assert {:ok, %{provider: :meridian}} = ok
  end

  test "requests without currencies are quoted exactly as before" do
    {_id, {:ok, %{rate: rate}}} = submit(%{})
    assert rate > 105_000_000 and rate < 110_000_000
  end

  test "the dashboard snapshot says which currencies each provider carries" do
    providers = Demo.snapshot().providers
    assert %{currencies: ~w(CNY USD EUR JPY KRW)} = Enum.find(providers, &(&1.id == :meridian))
    assert Enum.all?(providers, &is_list(&1.currencies))
  end
end
