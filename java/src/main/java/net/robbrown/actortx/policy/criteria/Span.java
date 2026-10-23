package net.robbrown.actortx.policy.criteria;

/**
 * The smallest and largest value across the candidates being judged, so a value can be placed
 * within the field.
 *
 * @param min the smallest value in the field
 * @param max the largest value in the field
 */
public record Span(long min, long max) {

  /** 0 at the field's minimum rising to 1 at its maximum; 1 when every candidate is equal. */
  public double higherIsBetter(long value) {
    return fraction(value - min);
  }

  /** 1 at the field's minimum falling to 0 at its maximum; 1 when every candidate is equal. */
  public double lowerIsBetter(long value) {
    return fraction(max - value);
  }

  private double fraction(long offset) {
    long spread = max - min;
    return spread == 0 ? 1.0 : (double) offset / spread;
  }
}
