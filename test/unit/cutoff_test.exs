defmodule ActorTxSpike.CutoffTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Cutoff

  defp ms(iso), do: iso |> DateTime.from_iso8601() |> elem(1) |> DateTime.to_unix(:millisecond)

  describe "new/1" do
    test "accepts string or atom keys; minute defaults to 0" do
      assert {:ok, %Cutoff{region: "UK", hour: 18, minute: 0, offset: 60}} =
               Cutoff.new(%{region: "UK", hour: 18, utc_offset_minutes: 60})
    end

    test "collects every error" do
      assert {:error, errors} =
               Cutoff.new(%{
                 "region" => "",
                 "hour" => 24,
                 "minute" => 60,
                 "utc_offset_minutes" => 9_999
               })

      assert length(errors) == 4

      assert "utc_offset_minutes: must be an integer in -840..840 or a function of a Date" in errors
    end

    test "an offset may be a function of the date (for DST rules)" do
      assert {:ok, _} =
               Cutoff.new(%{
                 "region" => "UK",
                 "hour" => 18,
                 "utc_offset_minutes" => fn _ -> 60 end
               })
    end
  end

  describe "next_ms/2" do
    test "later today in local time (UTC+1)" do
      {:ok, c} = Cutoff.new(%{"region" => "UK", "hour" => 18, "utc_offset_minutes" => 60})
      now = ms("2026-10-15T10:00:00Z")
      assert Cutoff.next_ms(c, now) == ms("2026-10-15T17:00:00Z")
      assert Cutoff.remaining_ms(c, now) == 7 * 3_600_000
    end

    test "rolls to tomorrow once today's cut-off has passed (and exactly at the instant)" do
      {:ok, c} = Cutoff.new(%{"region" => "UK", "hour" => 18, "utc_offset_minutes" => 60})
      assert Cutoff.next_ms(c, ms("2026-10-15T17:00:01Z")) == ms("2026-10-16T17:00:00Z")
      assert Cutoff.next_ms(c, ms("2026-10-15T17:00:00Z")) == ms("2026-10-16T17:00:00Z")
    end

    test "local date can differ from the UTC date (UTC-8 evening is next UTC day)" do
      {:ok, c} =
        Cutoff.new(%{
          "region" => "US-W",
          "hour" => 17,
          "minute" => 30,
          "utc_offset_minutes" => -480
        })

      assert Cutoff.next_ms(c, ms("2026-10-15T20:00:00Z")) == ms("2026-10-16T01:30:00Z")
      # 01:00Z on the 16th is still the 15th locally; today's 17:30 local = 01:30Z (the 16th)
      assert Cutoff.next_ms(c, ms("2026-10-16T01:00:00Z")) == ms("2026-10-16T01:30:00Z")
    end

    test "a date-aware offset function handles a DST change" do
      uk = fn date -> if Date.compare(date, ~D[2026-10-25]) == :lt, do: 60, else: 0 end
      {:ok, c} = Cutoff.new(%{"region" => "UK", "hour" => 18, "utc_offset_minutes" => uk})

      assert Cutoff.next_ms(c, ms("2026-10-24T10:00:00Z")) == ms("2026-10-24T17:00:00Z")
      assert Cutoff.next_ms(c, ms("2026-10-25T10:00:00Z")) == ms("2026-10-25T18:00:00Z")
    end

    test "fixed instants are returned as is" do
      c = Cutoff.fixed("TEST", 123_456)
      assert Cutoff.next_ms(c, 0) == 123_456
      assert Cutoff.remaining_ms(c, 100_000) == 23_456
      assert Cutoff.remaining_ms(c, 200_000) == -76_544
    end
  end
end
