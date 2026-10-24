package net.robbrown.actortx.audit;

/**
 * What the audit log has done for durability: records written, forces (fsync calls) attempted, and
 * the records those forces made durable. Records per sync is the group-commit figure: how many
 * records one force covered.
 *
 * @param records records written (each record counts once per log it is written to)
 * @param syncs forces attempted
 * @param syncedRecords records covered by forces that succeeded
 */
public record AuditStats(long records, long syncs, long syncedRecords) {

  /** No records and no forces. */
  public static final AuditStats NONE = new AuditStats(0, 0, 0);

  /**
   * The sum of two sets of figures, for a router over several logs.
   *
   * @param other the figures to add
   * @return the total
   */
  public AuditStats plus(AuditStats other) {
    return new AuditStats(
        records + other.records, syncs + other.syncs, syncedRecords + other.syncedRecords);
  }

  /**
   * Records covered per successful force; 0 before any force.
   *
   * @return the ratio
   */
  public double recordsPerSync() {
    return syncs == 0 ? 0 : (double) syncedRecords / syncs;
  }
}
