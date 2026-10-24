defmodule ActorTxSpike.PanelTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Events, Panel}

  defmodule Handler do
    def snapshot, do: %{providers: [%{id: :alpha, pid: self(), tup: {:a, 1}}]}
    def metrics, do: %{raw: [], latest: nil}
    def lab, do: %{scenarios: [%{id: "x", status: :pass}]}
    def bulk, do: %{status: :idle, submitted: 0}
    def review, do: %{open: 1, entries: []}

    def server,
      do: %{server: "Elixir", version: "1.18 / OTP 27", args: ["--lab"], settings: %{port: 4040}}

    def control(%{"action" => "ok"}), do: {:ok, %{done: true}}
    def control(%{"action" => "bad"}), do: {:error, "nope"}
    def control(_), do: :whatever
  end

  defmodule NoLabHandler do
    def snapshot, do: %{}
    def metrics, do: %{}
    def control(_), do: :ok
  end

  setup do
    {:ok, panel} =
      Panel.start_link(handler: Handler, port: 0, page: "<h1>stub</h1>", keepalive_ms: 30)

    {:ok, port: Panel.port(panel), panel: panel}
  end

  defp request(port, raw) do
    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false, packet: :raw])
    :ok = :gen_tcp.send(s, raw)
    result = recv_all(s, "")
    :gen_tcp.close(s)
    result
  end

  defp recv_all(s, acc) do
    case :gen_tcp.recv(s, 0, 1_000) do
      {:ok, data} -> recv_all(s, acc <> data)
      {:error, _} -> acc
    end
  end

  defp post(port, body, type \\ "application/json") do
    request(
      port,
      "POST /api/control HTTP/1.1\r\nhost: x\r\ncontent-type: #{type}\r\ncontent-length: #{byte_size(body)}\r\n\r\n#{body}"
    )
  end

  test "serves the page and a JSON snapshot", %{port: port} do
    assert request(port, "GET / HTTP/1.1\r\nhost: x\r\n\r\n") =~ ~r/200 OK.*<h1>stub<\/h1>/s

    resp = request(port, "GET /api/snapshot?x=1 HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ "application/json"
    assert resp =~ ~s("id":"alpha")
    assert resp =~ ~s("tup":["a",1])
  end

  test "serves metrics JSON", %{port: port} do
    resp = request(port, "GET /api/metrics HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ ~s("raw":[])
  end

  test "unknown routes are 404", %{port: port} do
    assert request(port, "GET /nope HTTP/1.1\r\nhost: x\r\n\r\n") =~ "404 Not Found"
  end

  test "control: success, handler error, odd reply", %{port: port} do
    assert post(port, ~s({"action":"ok"})) =~ ~s({"ok":true,"result":{"done":true}})
    assert post(port, ~s({"action":"bad"})) =~ ~r/422 Unprocessable.*"error":"nope"/s
    assert post(port, ~s({"action":"x"})) =~ ~r/200 OK.*\{"ok":true\}/s
  end

  @tag :regression

  test "control rejects non-JSON content types, bad JSON and non-objects", %{port: port} do
    assert post(port, ~s({"action":"ok"}), "text/plain") =~ "400 Bad Request"
    assert post(port, "{oops") =~ "400 Bad Request"
    assert post(port, "[1,2]") =~ "400 Bad Request"
  end

  @tag :regression

  test "oversized or invalid bodies get no response", %{port: port} do
    raw =
      "POST /api/control HTTP/1.1\r\nhost: x\r\ncontent-type: application/json\r\ncontent-length: 999999\r\n\r\n"

    assert request(port, raw) == ""

    assert request(port, "POST /api/control HTTP/1.1\r\nhost: x\r\ncontent-length: abc\r\n\r\n") ==
             ""
  end

  test "events stream sends live events and keeps the connection alive", %{port: port} do
    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false, packet: :raw])
    :ok = :gen_tcp.send(s, "GET /events HTTP/1.1\r\nhost: x\r\n\r\n")
    buffer = read_until(s, "", "keepalive")

    assert buffer =~ "text/event-stream"

    Events.emit("sse-1", {:provider, :alpha}, :received, "live one", %{})
    assert read_until(s, "", "live one") =~ ~s("actor":"provider:alpha")
    :gen_tcp.close(s)
  end

  @tag :regression

  test "the test lab endpoint serves the handler's lab, or 404s when there is none", %{port: port} do
    resp = request(port, "GET /api/lab HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ ~s("scenarios")

    {:ok, bare} = Panel.start_link(handler: NoLabHandler, port: 0, page: "x", keepalive_ms: 30)
    resp = request(Panel.port(bare), "GET /api/lab HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "404"
    assert resp =~ "no test lab"
  end

  test "the bulk endpoint serves the handler's progress, or 404s when there is none", %{
    port: port
  } do
    resp = request(port, "GET /api/bulk HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ ~s("submitted")

    {:ok, bare} = Panel.start_link(handler: NoLabHandler, port: 0, page: "x", keepalive_ms: 30)
    resp = request(Panel.port(bare), "GET /api/bulk HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "404"
    assert resp =~ "no bulk sender"
  end

  test "the review endpoint serves the handler's queue, or 404s when there is none", %{port: port} do
    resp = request(port, "GET /api/review HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ ~s("open")

    {:ok, bare} = Panel.start_link(handler: NoLabHandler, port: 0, page: "x", keepalive_ms: 30)
    resp = request(Panel.port(bare), "GET /api/review HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "404"
    assert resp =~ "no review queue"
  end

  test "the server endpoint says what is running and how it was started, or 404s", %{port: port} do
    resp = request(port, "GET /api/server HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "200 OK"
    assert resp =~ ~s("server":"Elixir")
    assert resp =~ ~s("--lab")

    {:ok, bare} = Panel.start_link(handler: NoLabHandler, port: 0, page: "x", keepalive_ms: 30)
    resp = request(Panel.port(bare), "GET /api/server HTTP/1.1\r\nhost: x\r\n\r\n")
    assert resp =~ "404"
    assert resp =~ "does not say what it is"
  end

  @tag :tmp_dir
  test "the perf-history endpoint serves the release list, or [] while there is none", %{
    tmp_dir: dir
  } do
    file = Path.join(dir, "results.json")
    get = "GET /api/perf-history HTTP/1.1\r\nhost: x\r\n\r\n"

    {:ok, none} = Panel.start_link(handler: Handler, port: 0, page: "x", perf_history: file)
    resp = request(Panel.port(none), get)
    assert resp =~ "200 OK"
    assert String.ends_with?(resp, "[]")

    File.write!(file, ~s([{"version":"0.2","runtime":"Elixir","tps":123.4}]))
    {:ok, has} = Panel.start_link(handler: Handler, port: 0, page: "x", perf_history: file)
    resp = request(Panel.port(has), get)
    assert resp =~ ~s("version":"0.2")
    assert resp =~ ~s("tps":123.4)

    File.write!(file, ~s({"not":"a list"}))
    {:ok, other} = Panel.start_link(handler: Handler, port: 0, page: "x", perf_history: file)
    assert request(Panel.port(other), get) |> String.ends_with?("[]")
  end

  test "a client that vanishes mid-stream or mid-request is tolerated", %{port: port} do
    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s, "GET /events HTTP/1.1\r\nhost: x\r\n\r\n")
    _ = read_until(s, "", "event-stream")
    :gen_tcp.close(s)

    for n <- 1..6 do
      Events.emit("gone", :payment, :tick, "tick #{n}", %{})
      Process.sleep(15)
    end

    {:ok, s2} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s2, "GET / HTTP/1.1\r\n")
    :gen_tcp.close(s2)

    assert request(port, "GET / HTTP/1.1\r\nhost: x\r\n\r\n") =~ "200 OK"
  end

  test "port conflicts are reported", %{port: port} do
    Process.flag(:trap_exit, true)
    assert {:error, {:listen_failed, _}} = Panel.start_link(handler: Handler, port: port)
  end

  test "stopping closes the listener", %{panel: panel, port: port} do
    GenServer.stop(panel)
    assert {:error, _} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false], 500)
  end

  test "sanitize makes any term JSON-safe" do
    out =
      Panel.sanitize(%{
        "string_key" => 1,
        1 => self(),
        :ref => make_ref(),
        fun: fn -> :ok end,
        tuple: {:ok, 1},
        bin: <<255, 254>>,
        struct: %Events.Event{seq: 1},
        text: "fine"
      })

    assert JSON.encode!(out) =~ "fine"
    assert is_binary(out["1"])
    assert out["tuple"] == [:ok, 1]
    assert out["struct"]["seq"] == 1
  end

  defp read_until(s, acc, needle) do
    if String.contains?(acc, needle) do
      acc
    else
      case :gen_tcp.recv(s, 0, 2_000) do
        {:ok, data} ->
          read_until(s, acc <> data, needle)

        {:error, reason} ->
          flunk("stream ended (#{inspect(reason)}) before #{inspect(needle)}: #{acc}")
      end
    end
  end
end
