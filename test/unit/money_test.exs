defmodule ActorTxSpike.MoneyTest do
  use ExUnit.Case, async: true
  alias ActorTxSpike.Money

  test "decimals convert exactly, not via binary floating point" do
    assert Money.rate(1.0877) == 108_770_000
    assert Money.rate(1.082) == 108_200_000
    assert Money.rate("1.0820") == 108_200_000
    assert Money.rate(1) == 100_000_000
    assert Money.rate(".5") == 50_000_000
    assert Money.rate("1.5e-1") == 15_000_000
    assert Money.rate("2e2") == 20_000_000_000
    assert Money.minor(12.34) == 1234
    assert Money.minor("-0.07") == -7
    assert Money.minor(0.1 + 0.2) == 30
  end

  test "excess precision rounds half-even" do
    assert Money.minor("0.125") == 12
    assert Money.minor("0.135") == 14
    assert Money.minor("0.1251") == 13
    assert Money.minor("0.1249") == 12
  end

  test "convert applies a rate to minor units with half-even rounding" do
    assert Money.convert(100_000, Money.rate(1.082)) == 108_200
    assert Money.convert(1, Money.rate(0.5)) == 0
    assert Money.convert(3, Money.rate(0.5)) == 2
    assert Money.convert(5, Money.rate(0.5)) == 2
    assert Money.convert(-3, Money.rate(0.5)) == -2
    assert Money.convert(7, Money.rate(0.3)) == 2
  end

  test "div_half_even covers below, above and exactly half" do
    assert Money.div_half_even(1, 4) == 0
    assert Money.div_half_even(3, 4) == 1
    assert Money.div_half_even(2, 4) == 0
    assert Money.div_half_even(6, 4) == 2
    assert Money.div_half_even(-2, 4) == 0
    assert Money.div_half_even(-6, 4) == -2
    assert_raise FunctionClauseError, fn -> Money.div_half_even(1, 0) end
  end

  test "formatting for display" do
    assert Money.format(108_500) == "1085.00"
    assert Money.format(-5) == "-0.05"
    assert Money.format(0) == "0.00"
    assert Money.format_rate(Money.rate(1.082)) == "1.0820"
    assert Money.format_rate(Money.rate(1.08765432)) == "1.08765432"
    assert Money.rate_to_float(108_200_000) == 1.082
    assert Money.to_major(1234) == 12.34
    assert Money.rate_scale() == 100_000_000
  end
end
