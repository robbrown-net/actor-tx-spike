defmodule ActorTxSpike.Audit do
  @moduledoc """
  A durable, append-only, hash-chained audit log of everything the system
  narrates. This is the evidence trail a payments operator shows an auditor
  (PCI DSS requirement 10: complete, time-stamped, tamper-evident logs;
  recordkeeping obligations under financial regulation).

  One JSON object per line:

      {"n":7,"seq":412,"at":1792253588647,"tx":"tx-1","actor":"payment",
       "kind":"tx_completed","message":"...","data":{...},
       "prev":"<hash of record 6>","hash":"<sha256(prev <> body)>"}

  Each record commits to the one before it, so editing, removing, inserting
  or reordering any record breaks the chain at that point and `verify/1`
  says where. What a chain alone cannot show is *truncation of the tail*:
  anchor `head/1` somewhere independent (a ticket, a separate system) at
  intervals to close that gap.

  The log refuses to start on top of a log that fails verification: a payments
  system must not keep appending to evidence it knows has been tampered with.

  Card numbers must never reach this log. `scrub/1` redacts any run of 13-19
  digits that passes the Luhn check, as defence in depth; the real control is
  that the orchestrator only ever handles provider tokens.
  """
  use GenServer

  alias ActorTxSpike.Audit.Lock
  alias ActorTxSpike.{Events, Panel}

  @genesis String.duplicate("0", 64)
  @sync_every_ms 50

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Append one event to the chain (also called by the subscription)."
  @spec record(GenServer.server(), Events.Event.t()) :: :ok
  def record(server, %Events.Event{} = event),
    do: GenServer.call(server, {:record, event})

  @doc "Number of records and the hash of the last one. Anchor this externally."
  @spec head(GenServer.server()) :: %{records: non_neg_integer(), head: String.t()}
  def head(server), do: GenServer.call(server, :head)

  @doc "Check a whole log file: sequence, chain links and every hash."
  @spec verify(Path.t()) ::
          {:ok, %{records: non_neg_integer(), head: String.t()}}
          | {:error, {:line, pos_integer(), atom()}}
  def verify(path) do
    if File.exists?(path), do: scan(path), else: {:ok, %{records: 0, head: @genesis}}
  end

  defp scan(path) do
    path
    |> File.stream!()
    |> Stream.with_index(1)
    |> Enum.reduce_while({:ok, %{records: 0, head: @genesis}}, &scan_line/2)
  end

  defp scan_line({line, i}, {:ok, acc}) do
    case check(line, acc) do
      {:ok, next} -> {:cont, {:ok, next}}
      {:error, why} -> {:halt, {:error, {:line, i, why}}}
    end
  end

  @doc "Redact anything that looks like a payment card number (13-19 digits, Luhn-valid)."
  @spec scrub(term()) :: term()
  def scrub(bin) when is_binary(bin) do
    Regex.replace(~r/\d(?:[ -]?\d){12,18}/, bin, fn candidate ->
      if luhn?(candidate), do: "[REDACTED-PAN]", else: candidate
    end)
  end

  def scrub(map) when is_map(map), do: Map.new(map, fn {k, v} -> {k, scrub(v)} end)
  def scrub(list) when is_list(list), do: Enum.map(list, &scrub/1)
  def scrub(other), do: other

  # ---- server ------------------------------------------------------------

  @impl true
  def init(opts) do
    path = Keyword.fetch!(opts, :path)

    with :ok <- Lock.acquire(path), {:ok, status} <- verified(path) do
      start(opts, path, status)
    end
  end

  # The lock is held for as long as the writer lives; if it cannot start it must not keep it.
  defp verified(path) do
    case verify(path) do
      {:ok, status} ->
        {:ok, status}

      {:error, reason} ->
        Lock.release(path)
        {:stop, {:audit_chain_broken, reason}}
    end
  end

  defp start(opts, path, %{records: n, head: head}) do
    {:ok, fd} = File.open(path, [:append, :binary, :raw])
    name = Keyword.get(opts, :name, __MODULE__)

    if Keyword.get(opts, :subscribe, true) do
      Events.subscribe()
      Events.register_sink({:audit, name}, self())
    end

    {:ok,
     %{
       path: path,
       fd: fd,
       n: n,
       head: head,
       sync: Keyword.get(opts, :sync, true),
       dirty: false,
       timer: nil,
       sink: if(Keyword.get(opts, :subscribe, true), do: {:audit, name})
     }}
  end

  @impl true
  def handle_call({:record, event}, _from, state), do: {:reply, :ok, append(state, event)}
  def handle_call(:flush, _from, state), do: {:reply, :ok, make_durable(state)}
  def handle_call(:head, _from, state), do: {:reply, %{records: state.n, head: state.head}, state}

  @impl true
  def handle_info({:tx_event, event}, state), do: {:noreply, append(state, event)}
  def handle_info(:sync, state), do: {:noreply, make_durable(%{state | timer: nil})}

  @impl true
  def terminate(reason, state) do
    # whatever the reason, the file is no longer ours; only a deliberate stop unregisters
    # the sink (a crash must leave it failed so the system fails closed)
    make_durable(state)
    :file.close(state.fd)
    Lock.release(state.path)

    if state.sink != nil and reason in [:normal, :shutdown],
      do: Events.unregister_sink(state.sink)

    :ok
  end

  # ---- internals ---------------------------------------------------------

  defp append(state, event) do
    n = state.n + 1

    # Scrub only the free-form fields: the chain's own hex digests are digit
    # runs that can look like card numbers and must never be touched.
    body = %{
      "n" => n,
      "seq" => event.seq,
      "at" => event.at_ms,
      "tx" => event.tx_id,
      "actor" => ActorTxSpike.Narrative.actor_label(event.actor),
      "kind" => to_string(event.kind),
      "message" => scrub(event.message),
      "data" => event.data |> Panel.sanitize() |> scrub(),
      "prev" => state.head
    }

    hash = hash(state.head, body)
    line = JSON.encode!(Map.put(body, "hash", hash)) <> "\n"
    :ok = :file.write(state.fd, line)
    schedule_sync(%{state | n: n, head: hash, dirty: true})
  end

  # Group commit: records are written at once, and made durable together, when a
  # barrier asks (the write-ahead point) or at the latest after `@sync_every_ms`.
  # One fsync then covers every record written since the last one, instead of one
  # per record (which would cap throughput at the disk's fsync rate).
  defp schedule_sync(%{sync: true, timer: nil} = state),
    do: %{state | timer: Process.send_after(self(), :sync, @sync_every_ms)}

  defp schedule_sync(state), do: state

  defp make_durable(%{sync: true, dirty: true} = state) do
    :ok = :file.datasync(state.fd)
    if state.timer, do: Process.cancel_timer(state.timer)
    %{state | dirty: false, timer: nil}
  end

  defp make_durable(state), do: state

  defp hash(prev, body) do
    :sha256 |> :crypto.hash(prev <> JSON.encode!(body)) |> Base.encode16(case: :lower)
  end

  defp check(line, %{records: n, head: head}) do
    with {:ok, %{} = record} <- JSON.decode(line) |> or_malformed(),
         {:ok, hash, body} <- split_hash(record),
         :ok <- expect(record["n"] == n + 1, :out_of_sequence),
         :ok <- expect(record["prev"] == head, :chain_broken),
         :ok <- expect(hash(head, body) == hash, :hash_mismatch) do
      {:ok, %{records: n + 1, head: hash}}
    end
  end

  defp or_malformed({:ok, value}), do: {:ok, value}
  defp or_malformed(_), do: {:error, :malformed}

  defp split_hash(%{"hash" => hash} = record) when is_binary(hash),
    do: {:ok, hash, Map.delete(record, "hash")}

  defp split_hash(_), do: {:error, :malformed}

  defp expect(true, _why), do: :ok
  defp expect(false, why), do: {:error, why}

  defp luhn?(candidate) do
    digits =
      candidate |> String.replace(~r/\D/, "") |> String.to_charlist() |> Enum.map(&(&1 - ?0))

    total =
      digits
      |> Enum.reverse()
      |> Enum.with_index()
      |> Enum.map(fn {d, i} -> if rem(i, 2) == 1, do: double(d), else: d end)
      |> Enum.sum()

    rem(total, 10) == 0
  end

  defp double(d) when d > 4, do: d * 2 - 9
  defp double(d), do: d * 2
end
