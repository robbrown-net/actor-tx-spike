package net.robbrown.actortx.service;

import java.util.List;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.selection.Participant;

/** Test double: a catalog over a few fake providers. */
public final class StubCatalog implements ProviderCatalog, AutoCloseable {

  private final List<ProviderServer> servers;
  private final List<Participant> participants;

  /** A catalog of these fakes, each carrying every currency. */
  public StubCatalog(FakeProvider... fakes) {
    this.servers = java.util.Arrays.stream(fakes).map(f -> new ProviderServer(f.id(), f)).toList();
    this.participants = servers.stream().map(s -> Participant.of(s.id(), s)).toList();
  }

  @Override
  public List<Participant> participants() {
    return participants;
  }

  @Override
  public List<ProviderServer> servers() {
    return servers;
  }

  @Override
  public List<ProviderView> views() {
    return servers.stream()
        .map(
            s ->
                new ProviderView(
                    s.id(),
                    s.state().wire(),
                    "closed",
                    0,
                    0,
                    0,
                    s.inFlight(),
                    null,
                    0,
                    1.0,
                    0,
                    0,
                    List.of()))
        .toList();
  }

  @Override
  public void close() {
    servers.forEach(ProviderServer::close);
  }
}
