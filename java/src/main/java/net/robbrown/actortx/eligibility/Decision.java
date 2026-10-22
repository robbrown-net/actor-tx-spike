package net.robbrown.actortx.eligibility;

import java.util.List;

/**
 * The outcome of {@link Eligibility#decide}, suitable for the journal.
 *
 * @param permitted whether nothing forbids the transaction
 * @param reasons why it is not permitted; empty when it is
 * @param regions the distinct regions touched, origin first
 * @param instrument the instrument, or {@code null} when the request named an unknown one
 * @param customerType the customer type, or {@code null} when the request named an unknown one
 * @param sourceCurrency the source currency as requested, or {@code null}
 * @param destinationCurrency the destination currency as requested, or {@code null}
 * @param providerRules the provider restrictions that applied (never the "all" rule)
 * @param logs the logs the records go to
 * @param retentionDays the longest retention that applies, or {@code null}
 */
public record Decision(
    boolean permitted,
    List<String> reasons,
    List<String> regions,
    Instrument instrument,
    CustomerType customerType,
    String sourceCurrency,
    String destinationCurrency,
    List<ProviderRule> providerRules,
    List<String> logs,
    Integer retentionDays) {}
