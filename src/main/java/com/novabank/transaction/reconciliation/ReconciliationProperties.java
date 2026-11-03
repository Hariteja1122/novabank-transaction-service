package com.novabank.transaction.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled    turn the scheduled run off (the bean stays, so it can still be run by hand)
 * @param interval   pause between runs on each instance
 * @param pendingAge a PENDING row younger than this may still have its request in flight
 * @param lease      how long one instance owns a row it claimed; must exceed one account-service call
 * @param batchSize  rows looked at per run
 */
@ConfigurationProperties(prefix = "novabank.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30S") Duration interval,
        @DefaultValue("PT2M") Duration pendingAge,
        @DefaultValue("PT2M") Duration lease,
        @DefaultValue("50") int batchSize) {
}
