package net.robbrown.actortx.events;

import java.util.Map;

/**
 * One thing that happened: the unit of the narrative, the audit log and the journal.
 *
 * @param seq a number that only goes up, in the order events were announced
 * @param atMs wall-clock milliseconds when it was announced
 * @param txId the transaction it is about, or {@code null} for the system
 * @param actor who is speaking ({@code "payment"}, {@code "provider:alpha"}, {@code "journal"}...)
 * @param kind what happened ({@code "step_started"}...)
 * @param message the sentence a person reads
 * @param data structured detail for machines (JSON-serialisable values only)
 */
public record Event(
    long seq,
    long atMs,
    String txId,
    String actor,
    String kind,
    String message,
    Map<String, Object> data) {}
