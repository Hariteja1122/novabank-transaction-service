package com.novabank.transaction.reconciliation;

import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.client.AccountClient.LedgerEntry;
import com.novabank.transaction.client.AccountClient.LedgerOutcome;
import com.novabank.transaction.common.ApiException;
import com.novabank.transaction.reconciliation.PendingTransactionReconciler.RunResult;
import com.novabank.transaction.support.Db2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Runs several reconcilers at once against real Db2, the way several Cloud Run instances
 * would. account-service is a mock so the test controls the ledger's answers and counts
 * how often each reference is asked about.
 */
@TestPropertySource(properties = {
        "novabank.reconciliation.enabled=false", // the test drives runs itself
        "novabank.reconciliation.lease=PT2S",
})
class ReconciliationIT extends Db2IntegrationTest {

    @Autowired
    PendingTransactionReconciler reconciler;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountClient accounts;

    @Test
    void concurrentInstancesSettleEachPendingTransferExactlyOnce() throws Exception {
        int rows = 30;
        List<String> refs = IntStream.range(0, rows).mapToObj(i -> "IT-MI-%02d".formatted(i)).toList();
        refs.forEach(ref -> insert(ref, "PENDING", 5));
        insert("IT-MI-RECENT", "PENDING", 0);   // request may still be in flight: leave alone
        insert("IT-MI-DONE", "COMPLETED", 5);   // already settled: leave alone

        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        when(accounts.voidUnlessApplied(anyString())).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            calls.computeIfAbsent(ref, r -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(25); // widen the window in which instances overlap
            boolean applied = ref.endsWith("0") || ref.endsWith("2") || ref.endsWith("4") || ref.endsWith("6") || ref.endsWith("8");
            return new LedgerEntry(ref, applied ? LedgerOutcome.APPLIED : LedgerOutcome.VOIDED, Instant.now());
        });

        List<RunResult> results = runAsInstances(4);

        assertThat(calls.keySet()).as("only old PENDING rows are reconciled").containsExactlyInAnyOrderElementsOf(refs);
        assertThat(calls.values()).as("each reference settled by exactly one instance")
                .allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(results.stream().mapToInt(r -> r.completed() + r.failed()).sum()).isEqualTo(rows);

        for (String ref : refs) {
            int n = Integer.parseInt(ref.substring(ref.length() - 2));
            Map<String, Object> row = row(ref);
            assertThat(row.get("RECON_LEASE_UNTIL")).as("lease released for " + ref).isNull();
            if (n % 2 == 0) {
                assertThat(row.get("STATUS")).isEqualTo("COMPLETED");
                assertThat(row.get("COMPLETED_AT")).isNotNull();
            } else {
                assertThat(row.get("STATUS")).isEqualTo("FAILED");
                assertThat(row.get("FAILURE_REASON")).isEqualTo(PendingTransactionReconciler.VOIDED_REASON);
            }
        }
        assertThat(row("IT-MI-RECENT").get("STATUS")).isEqualTo("PENDING");
        assertThat(row("IT-MI-DONE").get("STATUS")).isEqualTo("COMPLETED");
    }

    @Test
    void accountServiceOutageLeavesRowsPendingUntilItRecovers() throws Exception {
        insert("IT-DOWN-1", "PENDING", 7);
        insert("IT-DOWN-2", "PENDING", 6);
        when(accounts.voidUnlessApplied(anyString()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Account service is unavailable"));

        RunResult outage = reconciler.runOnce();

        assertThat(outage.deferred()).isEqualTo(1);
        assertThat(row("IT-DOWN-1").get("STATUS")).isEqualTo("PENDING");
        assertThat(row("IT-DOWN-1").get("RECON_LEASE_UNTIL")).as("claimed, so other instances back off").isNotNull();
        assertThat(row("IT-DOWN-2").get("STATUS")).isEqualTo("PENDING");

        // doAnswer, not when(): when() would call the mock, which is still stubbed to throw.
        doAnswer(inv -> new LedgerEntry(inv.getArgument(0), LedgerOutcome.APPLIED, Instant.now()))
                .when(accounts).voidUnlessApplied(anyString());
        reconciler.runOnce();
        assertThat(row("IT-DOWN-1").get("STATUS")).as("still leased from the failed attempt").isEqualTo("PENDING");
        assertThat(row("IT-DOWN-2").get("STATUS")).isEqualTo("COMPLETED");

        Thread.sleep(2_500); // let the 2 second test lease run out
        reconciler.runOnce();
        assertThat(row("IT-DOWN-1").get("STATUS")).isEqualTo("COMPLETED");
    }

    private void insert(String reference, String status, int minutesAgo) {
        // CURRENT TIMESTAMP is UTC inside the Db2 container, matching hibernate.jdbc.time_zone.
        jdbc.update("""
                INSERT INTO NB_TXN.TRANSACTIONS (REFERENCE, IDEMPOTENCY_KEY, TXN_TYPE, FROM_ACCOUNT, TO_ACCOUNT, AMOUNT,
                    STATUS, DESCRIPTION, INITIATED_BY, CREATED_AT, COMPLETED_AT)
                VALUES (?, ?, 'TRANSFER', '100245180031', '100245180057', 1.00, ?, 'Reconciliation IT', 3,
                    CURRENT TIMESTAMP - %d MINUTES, %s)""".formatted(minutesAgo,
                        "COMPLETED".equals(status) ? "CURRENT TIMESTAMP" : "NULL"),
                reference, "key-" + reference, status);
    }

    private Map<String, Object> row(String reference) {
        return jdbc.queryForMap("SELECT STATUS, COMPLETED_AT, FAILURE_REASON, RECON_LEASE_UNTIL FROM NB_TXN.TRANSACTIONS WHERE REFERENCE = ?",
                reference);
    }

    private List<RunResult> runAsInstances(int instances) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RunResult>> futures = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reconciler.runOnce();
                }));
            }
            start.countDown();
            List<RunResult> results = new ArrayList<>();
            for (Future<RunResult> f : futures) {
                results.add(f.get(2, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
