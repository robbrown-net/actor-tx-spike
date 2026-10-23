package net.robbrown.actortx.payment;

import net.robbrown.actortx.provider.Operation;

/**
 * How to undo a step that was done.
 *
 * @param operation the operation that undoes it
 * @param verb what it is called in the story ("cancel the accepted quote")
 */
public record Compensation(Operation operation, String verb) {}
