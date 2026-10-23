defmodule ActorTxSpike.Panel do
  @moduledoc """
  A tiny dependency-free HTTP server for the live dashboard.

  * `GET /`              the dashboard page (`priv/panel.html`)
  * `GET /events`        Server-Sent Events: replays recent history, then streams live
  * `GET /api/snapshot`  JSON from `handler.snapshot()`
  * `GET /api/metrics`   JSON from `handler.metrics()` (resource and application metrics)
  * `GET /api/lab`       JSON from `handler.lab()` if the handler offers one (the test lab)
  * `GET /api/review`    JSON from `handler.review()` if the handler offers one (the review queue)
  * `GET /api/bulk`      JSON from `handler.bulk()` if the handler offers one (bulk sending progress)
  * `GET /api/server`    JSON from `handler.server()` if the handler offers one (what is running and how it was started)
  * `POST /api/control`  JSON body passed to `handler.control/1`

  Binds to loopback only by default. POSTs must be `application/json`, which a
  foreign web page cannot send to us without a CORS preflight we never grant.
  """
  use GenServer

  alias ActorTxSpike.Events

  @keepalive_ms 15_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts)

  @doc "The TCP port actually bound (useful with `port: 0`)."
  def port(server), do: GenServer.call(server, :port)

  @impl true
  def init(opts) do
    ip = Keyword.get(opts, :ip, {127, 0, 0, 1})

    cfg = %{
      handler: Keyword.fetch!(opts, :handler),
      page: Keyword.get_lazy(opts, :page, &page/0),
      keepalive_ms: Keyword.get(opts, :keepalive_ms, @keepalive_ms)
    }

    case :gen_tcp.listen(Keyword.get(opts, :port, 4040), [
           :binary,
           active: false,
           reuseaddr: true,
           ip: ip,
           packet: :http_bin
         ]) do
      {:ok, listen} ->
        {:ok, port} = :inet.port(listen)
        {:ok, acceptor} = Task.start_link(fn -> accept_loop(listen, cfg) end)
        {:ok, %{listen: listen, port: port, acceptor: acceptor}}

      {:error, reason} ->
        {:stop, {:listen_failed, reason}}
    end
  end

  @impl true
  def handle_call(:port, _from, state), do: {:reply, state.port, state}

  @impl true
  def terminate(_reason, state), do: :gen_tcp.close(state.listen)

  defp page do
    File.read!(Path.join(:code.priv_dir(:actor_tx_spike), "panel.html"))
  end

  # ---- connections -------------------------------------------------------

  defp accept_loop(listen, cfg) do
    case :gen_tcp.accept(listen) do
      {:ok, socket} ->
        pid =
          spawn(fn ->
            receive do
              :go -> serve(socket, cfg)
            end
          end)

        :ok = :gen_tcp.controlling_process(socket, pid)
        send(pid, :go)
        accept_loop(listen, cfg)

      {:error, _closed} ->
        :ok
    end
  end

  defp serve(socket, cfg) do
    with {:ok, {:http_request, method, {:abs_path, path}, _}} <- :gen_tcp.recv(socket, 0, 5_000),
         {:ok, headers} <- read_headers(socket, %{}),
         {:ok, body} <- read_body(socket, headers) do
      route(socket, method, path |> to_string() |> String.split("?") |> hd(), headers, body, cfg)
    end

    :gen_tcp.close(socket)
  end

  defp read_headers(socket, acc) do
    case :gen_tcp.recv(socket, 0, 5_000) do
      {:ok, {:http_header, _, name, _, value}} ->
        read_headers(
          socket,
          Map.put(acc, name |> to_string() |> String.downcase(), to_string(value))
        )

      {:ok, :http_eoh} ->
        {:ok, acc}

      other ->
        {:error, other}
    end
  end

  defp read_body(socket, headers) do
    headers |> Map.get("content-length", "0") |> Integer.parse() |> body(socket)
  end

  defp body({0, _}, _socket), do: {:ok, ""}

  defp body({len, _}, socket) when len > 0 and len <= 65_536 do
    :inet.setopts(socket, packet: :raw)
    :gen_tcp.recv(socket, len, 5_000)
  end

  defp body(_other, _socket), do: {:error, :bad_length}

  # A page some handlers offer and others do not: `handler.name()` or a 404.
  defp optional(socket, handler, name, message) do
    if Code.ensure_loaded?(handler) and function_exported?(handler, name, 0),
      do: respond_json(socket, 200, apply(handler, name, [])),
      else: respond_json(socket, 404, %{ok: false, error: message})
  end

  # ---- routes ------------------------------------------------------------

  defp route(socket, :GET, "/", _h, _b, cfg),
    do: respond(socket, 200, "text/html; charset=utf-8", cfg.page)

  defp route(socket, :GET, "/api/snapshot", _h, _b, cfg),
    do: respond_json(socket, 200, cfg.handler.snapshot())

  defp route(socket, :GET, "/api/metrics", _h, _b, cfg),
    do: respond_json(socket, 200, cfg.handler.metrics())

  defp route(socket, :GET, "/api/lab", _h, _b, cfg),
    do: optional(socket, cfg.handler, :lab, "this handler has no test lab")

  defp route(socket, :GET, "/api/review", _h, _b, cfg),
    do: optional(socket, cfg.handler, :review, "this handler has no review queue")

  defp route(socket, :GET, "/api/bulk", _h, _b, cfg),
    do: optional(socket, cfg.handler, :bulk, "this handler has no bulk sender")

  defp route(socket, :GET, "/api/server", _h, _b, cfg),
    do: optional(socket, cfg.handler, :server, "this handler does not say what it is")

  defp route(socket, :GET, "/events", _h, _b, cfg), do: stream_events(socket, cfg.keepalive_ms)

  defp route(socket, :POST, "/api/control", headers, body, %{handler: handler}) do
    with true <- json?(headers),
         {:ok, command} <- decode(body),
         true <- is_map(command) do
      case handler.control(command) do
        {:ok, result} -> respond_json(socket, 200, %{ok: true, result: result})
        {:error, message} -> respond_json(socket, 422, %{ok: false, error: message})
        _other -> respond_json(socket, 200, %{ok: true})
      end
    else
      _ -> respond_json(socket, 400, %{ok: false, error: "expected a JSON object"})
    end
  end

  defp route(socket, _method, _path, _h, _b, _cfg),
    do: respond(socket, 404, "text/plain", "not found")

  defp json?(headers),
    do: String.contains?(Map.get(headers, "content-type", ""), "application/json")

  defp decode(body) do
    {:ok, JSON.decode!(body)}
  rescue
    _ -> :error
  end

  defp respond_json(socket, status, term),
    do: respond(socket, status, "application/json", term |> sanitize() |> JSON.encode!())

  defp respond(socket, status, type, body) do
    head =
      "HTTP/1.1 #{status} #{reason(status)}\r\ncontent-type: #{type}\r\n" <>
        "content-length: #{byte_size(body)}\r\ncache-control: no-store\r\nconnection: close\r\n\r\n"

    :gen_tcp.send(socket, [head, body])
  end

  defp reason(200), do: "OK"
  defp reason(400), do: "Bad Request"
  defp reason(404), do: "Not Found"
  defp reason(422), do: "Unprocessable Entity"

  # ---- server-sent events -------------------------------------------------

  defp stream_events(socket, keepalive_ms) do
    head =
      "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ncache-control: no-store\r\n" <>
        "connection: keep-alive\r\n\r\n"

    with :ok <- :gen_tcp.send(socket, head) do
      # A client that vanished during replay is noticed by the next send in stream/2.
      Enum.each(Events.subscribe(), &:gen_tcp.send(socket, encode(&1)))
      stream(socket, keepalive_ms)
    end
  end

  defp stream(socket, keepalive_ms) do
    chunk =
      receive do
        {:tx_event, event} -> encode(event)
      after
        keepalive_ms -> ": keepalive\n\n"
      end

    case :gen_tcp.send(socket, chunk) do
      :ok -> stream(socket, keepalive_ms)
      _closed -> :closed
    end
  end

  defp encode(event) do
    payload = %{
      seq: event.seq,
      at: event.at_ms,
      tx: event.tx_id,
      actor: ActorTxSpike.Narrative.actor_label(event.actor),
      kind: event.kind,
      message: event.message,
      data: event.data
    }

    ["id: #{event.seq}\ndata: ", payload |> sanitize() |> JSON.encode!(), "\n\n"]
  end

  # ---- JSON-safe terms ---------------------------------------------------

  @doc false
  def sanitize(%{__struct__: _} = struct), do: struct |> Map.from_struct() |> sanitize()
  def sanitize(map) when is_map(map), do: Map.new(map, fn {k, v} -> {key(k), sanitize(v)} end)
  def sanitize(list) when is_list(list), do: Enum.map(list, &sanitize/1)
  def sanitize(tuple) when is_tuple(tuple), do: tuple |> Tuple.to_list() |> sanitize()
  def sanitize(pid) when is_pid(pid), do: inspect(pid)
  def sanitize(ref) when is_reference(ref), do: inspect(ref)
  def sanitize(fun) when is_function(fun), do: inspect(fun)
  def sanitize(bin) when is_binary(bin), do: if(String.valid?(bin), do: bin, else: inspect(bin))
  def sanitize(other), do: other

  defp key(k) when is_binary(k), do: k
  defp key(k) when is_atom(k), do: Atom.to_string(k)
  defp key(k), do: inspect(k)
end
