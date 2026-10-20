defmodule ActorTxSpike.Validation do
  @moduledoc """
  The shared shape of "validate plain data into a struct": go through a list of
  fields, parse each one that is present, keep what parsed and collect a message
  for every one that did not, so a caller sees all the problems at once.

  A field is `{name, key, parse}` where `parse` returns `{:ok, value}` or
  `{:error, message}`. Fields that are absent are left to the struct's defaults.
  """

  @type field :: {atom(), String.t(), (term() -> {:ok, term()} | {:error, String.t()})}

  @doc """
  Parse the `fields` found in `raw` (string keys). Error messages are
  `"<prefix><key>: <message>"`; `errors` are any already collected.
  """
  @spec parse_fields(map(), [field()], String.t(), [String.t()]) :: {map(), [String.t()]}
  def parse_fields(raw, fields, prefix, errors) do
    Enum.reduce(fields, {%{}, errors}, &field(&1, raw, prefix, &2))
  end

  defp field({name, key, parse}, raw, prefix, acc) do
    case Map.fetch(raw, key) do
      :error -> acc
      {:ok, value} -> record(parse.(value), name, prefix <> key, acc)
    end
  end

  defp record({:ok, parsed}, name, _where, {values, errors}),
    do: {Map.put(values, name, parsed), errors}

  defp record({:error, message}, _name, where, {values, errors}),
    do: {values, errors ++ ["#{where}: #{message}"]}

  @doc "Build `struct` if every check holds, else every message of the checks that did not."
  @spec build(struct(), [{boolean(), String.t()}]) :: {:ok, struct()} | {:error, [String.t()]}
  def build(struct, checks) do
    case for({false, message} <- checks, do: message) do
      [] -> {:ok, struct}
      errors -> {:error, errors}
    end
  end

  @doc "A non-empty string."
  @spec present?(term()) :: boolean()
  def present?(value), do: is_binary(value) and value != ""

  @doc "An integer within `range`."
  @spec in_range?(term(), Range.t()) :: boolean()
  def in_range?(value, range), do: is_integer(value) and value in range

  @doc "A map with its keys as strings (anything that is not a map is an empty one)."
  @spec stringify(term()) :: map()
  def stringify(map) when is_map(map), do: Map.new(map, fn {k, v} -> {to_string(k), v} end)
  def stringify(_other), do: %{}
end
