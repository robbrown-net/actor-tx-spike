package net.robbrown.actortx.service;

import java.util.List;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.selection.Participant;

/** The providers the system can ask: who they are, and how they look right now. */
public interface ProviderCatalog {

  /** The providers selection may ask. */
  List<Participant> participants();

  /** The provider servers themselves, for the sentinel. */
  List<ProviderServer> servers();

  /** The providers as the dashboard shows them. */
  List<ProviderView> views();
}
