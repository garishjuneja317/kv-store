import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StorageEngineTest — pure JDK test suite for {@link StorageEngine}.
 *
 * <p>No JUnit, TestNG, or any third-party dependency.  The harness is driven
 * by a static {@code main} method that accumulates pass/fail counts and exits
 * with a non-zero status code on any failure, making it CI-friendly.
 *
 * <h2>Test Categories</h2>
 * <ol>
 *   <li>Basic CRUD — set/get/delete round-trips</li>
 *   <li>Persistence — data survives engine close and re-open</li>
 *   <li>WAL Compaction — stale entries are eliminated; live data survives</li>
 *   <li>Edge Cases — empty values, Unicode, pipes, colons, newlines in value</li>
 *   <li>Concurrent Writes — correctness under concurrent threads</li>
 *   <li>Concurrent Reads — read throughput with shared lock</li>
 *   <li>Delete of Missing Key — no exception, idempotent tombstone</li>
 *   <li>Recovery Ordering — last-write wins on repeated keys</li>
 *   <li>Large Value — multi-KiB values survive round-trip</li>
 *   <li>Stats Reporting — stats() returns a non-empty string with key count</li>
 *   <li>TTL: key not yet expired — value still accessible</li>
 *   <li>TTL: key expired on GET — lazy eviction returns empty</li>
 *   <li>TTL: persistence across reopen — TTL survives WAL replay</li>
 *   <li>TTL: compaction excludes expired keys</li>
 *   <li>TTL: overwrite clears previous TTL</li>
 * </ol>
 */
public class StorageEngineTest {

    // -------------------------------------------------------------------------
    // Assertion harness
    // -------------------------------------------------------------------------

    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();

    /** Asserts that {@code condition} is {@code true}. */
    static void assertTrue(String label, boolean condition) {
        if (condition) {
            System.out.println("  \u001B[92m[PASS]\u001B[0m " + label);
            passed.incrementAndGet();
        } else {
            System.out.println("  \u001B[91m[FAIL]\u001B[0m " + label);
            failed.incrementAndGet();
        }
    }

    /** Asserts that {@code actual} equals {@code expected}. */
    static void assertEquals(String label, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        if (ok) {
            System.out.println("  \u001B[92m[PASS]\u001B[0m " + label);
            passed.incrementAndGet();
        } else {
            System.out.println("  \u001B[91m[FAIL]\u001B[0m " + label +
                " — expected: " + expected + ", got: " + actual);
            failed.incrementAndGet();
        }
    }

    /** Asserts that {@code actual} is NOT equal to {@code unexpected}. */
    static void assertNotEquals(String label, Object unexpected, Object actual) {
        boolean ok = !Objects.equals(unexpected, actual);
        if (ok) {
            System.out.println("  \u001B[92m[PASS]\u001B[0m " + label);
            passed.incrementAndGet();
        } else {
            System.out.println("  \u001B[91m[FAIL]\u001B[0m " + label +
                " — expected NOT: " + unexpected + ", but got: " + actual);
            failed.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a fresh temporary WAL path for each test. */
    static Path tempWal(String suffix) throws IOException {
        Path dir = Files.createTempDirectory("kv-test-");
        return dir.resolve("test-" + suffix + ".wal");
    }

    /** Deletes a file silently (best-effort). */
    static void silentDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    /** Sleeps for the given number of milliseconds, ignoring interruption. */
    static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("\u001B[1m\u001B[96m");
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     StorageEngine Test Suite (pure JDK)      ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("\u001B[0m");

        testBasicCrud();
        testPersistenceAcrossReopen();
        testCompaction();
        testEdgeCaseKeys();
        testEdgeCaseValues();
        testDeleteMissingKey();
        testRecoveryLastWriteWins();
        testLargeValue();
        testStatsReporting();
        testConcurrentWrites();
        testConcurrentReads();

        // TTL tests
        testTtlNotYetExpired();
        testTtlExpiredOnGet();
        testTtlPersistenceAcrossReopen();
        testTtlCompactionExcludesExpired();
        testTtlOverwriteClearsTtl();

        // -------------------------------------------------------------------------
        // Summary
        // -------------------------------------------------------------------------
        System.out.println();
        System.out.println("\u001B[1m══════════ Results ══════════\u001B[0m");
        System.out.printf("  Total  : %d%n", passed.get() + failed.get());
        System.out.printf("  \u001B[92mPassed : %d\u001B[0m%n", passed.get());
        System.out.printf("  \u001B[91mFailed : %d\u001B[0m%n", failed.get());
        System.out.println("\u001B[1m═════════════════════════════\u001B[0m");

        if (failed.get() > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Test methods — existing
    // =========================================================================

    // -------------------------------------------------------------------------
    // 1. Basic CRUD
    // -------------------------------------------------------------------------
    static void testBasicCrud() throws Exception {
        System.out.println("\n\u001B[1m[1] Basic CRUD\u001B[0m");
        Path wal = tempWal("crud");
        try (StorageEngine e = new StorageEngine(wal)) {

            e.set("hello", "world");
            assertEquals("GET after SET returns correct value",
                Optional.of("world"), e.get("hello"));

            e.set("hello", "updated");
            assertEquals("GET after overwrite returns new value",
                Optional.of("updated"), e.get("hello"));

            e.delete("hello");
            assertEquals("GET after DELETE returns empty",
                Optional.empty(), e.get("hello"));

            assertEquals("GET of non-existent key returns empty",
                Optional.empty(), e.get("nonexistent"));

            e.set("alpha", "1");
            e.set("beta", "2");
            assertEquals("Multiple keys — alpha", Optional.of("1"), e.get("alpha"));
            assertEquals("Multiple keys — beta",  Optional.of("2"), e.get("beta"));

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Persistence across engine re-instantiation
    // -------------------------------------------------------------------------
    static void testPersistenceAcrossReopen() throws Exception {
        System.out.println("\n\u001B[1m[2] Persistence (close → reopen)\u001B[0m");
        Path wal = tempWal("persist");
        try {
            // Write, then close.
            try (StorageEngine e = new StorageEngine(wal)) {
                e.set("user:1", "Alice");
                e.set("user:2", "Bob");
                e.set("user:3", "Carol");
                e.delete("user:2");
            }

            // Reopen: engine must replay the WAL.
            try (StorageEngine e = new StorageEngine(wal)) {
                assertEquals("user:1 survives reopen", Optional.of("Alice"), e.get("user:1"));
                assertEquals("user:2 deleted before reopen", Optional.empty(), e.get("user:2"));
                assertEquals("user:3 survives reopen", Optional.of("Carol"), e.get("user:3"));
                assertEquals("Live key count after recovery", 2, e.liveKeyCount());
            }
        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 3. WAL Compaction
    // -------------------------------------------------------------------------
    static void testCompaction() throws Exception {
        System.out.println("\n\u001B[1m[3] WAL Compaction\u001B[0m");
        Path wal = tempWal("compact");
        try (StorageEngine e = new StorageEngine(wal)) {

            // Write many versions of the same key to bloat the WAL.
            for (int i = 0; i < 100; i++) {
                e.set("x", "value-" + i);
            }
            e.set("keep", "me");
            e.set("del", "gone");
            e.delete("del");

            long sizeBeforeCompact = Files.size(wal);

            e.compact();

            long sizeAfterCompact = Files.size(wal);
            assertTrue("WAL shrinks after compact",
                sizeAfterCompact < sizeBeforeCompact);

            assertEquals("Latest value survives compact",
                Optional.of("value-99"), e.get("x"));
            assertEquals("Non-overwritten key survives compact",
                Optional.of("me"), e.get("keep"));
            assertEquals("Deleted key absent after compact",
                Optional.empty(), e.get("del"));
            assertEquals("Live key count after compact", 2, e.liveKeyCount());

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 4. Edge-case keys
    // -------------------------------------------------------------------------
    static void testEdgeCaseKeys() throws Exception {
        System.out.println("\n\u001B[1m[4] Edge-Case Keys\u001B[0m");
        Path wal = tempWal("edge-keys");
        try (StorageEngine e = new StorageEngine(wal)) {

            String[] keys = {
                "key:with:colons",
                "key|with|pipes",
                "key with spaces",
                "key\twith\ttabs",
                "日本語キー",
                "emoji🔑key",
                "UPPERCASE_KEY_123",
                "mixedCase_Key-with.dots",
            };

            for (String k : keys) {
                e.set(k, "value-for-" + k);
            }
            for (String k : keys) {
                assertEquals("Round-trip for key: " + k,
                    Optional.of("value-for-" + k), e.get(k));
            }

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 5. Edge-case values
    // -------------------------------------------------------------------------
    static void testEdgeCaseValues() throws Exception {
        System.out.println("\n\u001B[1m[5] Edge-Case Values\u001B[0m");
        Path wal = tempWal("edge-vals");
        try (StorageEngine e = new StorageEngine(wal)) {

            e.set("empty",      "");
            e.set("spaces",     "   ");
            e.set("pipes",      "a|b|c|d");
            e.set("colon",      "host:8080/path?q=1&r=2");
            e.set("unicode",    "こんにちは世界🌏");
            e.set("json-like",  "{\"key\":\"value\",\"num\":42}");
            e.set("multiline",  "line one\nline two\nline three");

            assertEquals("Empty value",   Optional.of(""),    e.get("empty"));
            assertEquals("Spaces value",  Optional.of("   "), e.get("spaces"));
            assertEquals("Pipe chars",    Optional.of("a|b|c|d"), e.get("pipes"));
            assertEquals("Colon in value",
                Optional.of("host:8080/path?q=1&r=2"), e.get("colon"));
            assertEquals("Unicode value",
                Optional.of("こんにちは世界🌏"), e.get("unicode"));
            assertEquals("JSON-like value",
                Optional.of("{\"key\":\"value\",\"num\":42}"), e.get("json-like"));
            assertEquals("Multiline value (embedded newline)",
                Optional.of("line one\nline two\nline three"), e.get("multiline"));

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 6. Delete of missing key (must not throw)
    // -------------------------------------------------------------------------
    static void testDeleteMissingKey() throws Exception {
        System.out.println("\n\u001B[1m[6] Delete of Missing Key\u001B[0m");
        Path wal = tempWal("del-missing");
        try (StorageEngine e = new StorageEngine(wal)) {

            boolean threw = false;
            try {
                e.delete("this-key-does-not-exist");
            } catch (Exception ex) {
                threw = true;
            }
            assertTrue("DELETE of missing key does not throw", !threw);
            assertEquals("GET after DELETE-missing returns empty",
                Optional.empty(), e.get("this-key-does-not-exist"));

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 7. Recovery ordering — last write wins
    // -------------------------------------------------------------------------
    static void testRecoveryLastWriteWins() throws Exception {
        System.out.println("\n\u001B[1m[7] Recovery Last-Write Wins\u001B[0m");
        Path wal = tempWal("lww");
        try {
            try (StorageEngine e = new StorageEngine(wal)) {
                e.set("k", "v1");
                e.set("k", "v2");
                e.set("k", "v3");
            }
            try (StorageEngine e = new StorageEngine(wal)) {
                assertEquals("Last write wins after recovery",
                    Optional.of("v3"), e.get("k"));
            }
        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 8. Large value round-trip
    // -------------------------------------------------------------------------
    static void testLargeValue() throws Exception {
        System.out.println("\n\u001B[1m[8] Large Value Round-Trip\u001B[0m");
        Path wal = tempWal("large");
        try (StorageEngine e = new StorageEngine(wal)) {

            // 1 MiB value.
            char[] chars = new char[1024 * 1024];
            Arrays.fill(chars, 'A');
            String big = new String(chars);

            e.set("big", big);
            Optional<String> result = e.get("big");
            assertTrue("Large value is present",    result.isPresent());
            assertEquals("Large value length", 1024 * 1024, result.get().length());
            assertEquals("Large value content matches", big, result.get());

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 9. Stats reporting
    // -------------------------------------------------------------------------
    static void testStatsReporting() throws Exception {
        System.out.println("\n\u001B[1m[9] Stats Reporting\u001B[0m");
        Path wal = tempWal("stats");
        try (StorageEngine e = new StorageEngine(wal)) {
            e.set("a", "1");
            e.set("b", "2");
            e.get("a");
            e.delete("b");

            String stats = e.stats();
            assertTrue("stats() returns non-empty string",
                stats != null && !stats.isBlank());
            assertTrue("stats() contains key count",
                stats.contains("Live keys"));
            assertTrue("stats() contains WAL size",
                stats.contains("WAL file size"));
            assertTrue("stats() contains SET count",
                stats.contains("SET  ops"));
            assertTrue("stats() contains GET count",
                stats.contains("GET  ops"));
            assertTrue("stats() contains DELETE count",
                stats.contains("DELETE ops"));
            assertTrue("stats() contains expired purged counter",
                stats.contains("Expired purged"));

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 10. Concurrent writes — correctness under N writer threads
    // -------------------------------------------------------------------------
    static void testConcurrentWrites() throws Exception {
        System.out.println("\n\u001B[1m[10] Concurrent Writes\u001B[0m");
        Path wal = tempWal("concurrent-write");
        int numThreads   = 16;
        int opsPerThread = 500;

        try (StorageEngine e = new StorageEngine(wal)) {
            ExecutorService pool = Executors.newFixedThreadPool(numThreads);
            CountDownLatch  latch = new CountDownLatch(numThreads);
            List<Future<Void>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    latch.countDown();
                    latch.await(); // Align all threads for maximum contention.
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "thread-" + threadId + "-key-" + i;
                        e.set(key, "val-" + i);
                    }
                    return null;
                }));
            }

            // Collect exceptions.
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            int expectedKeys = numThreads * opsPerThread;
            assertEquals("All concurrent writes are visible",
                expectedKeys, e.liveKeyCount());

            // Spot-check a few.
            for (int t = 0; t < numThreads; t++) {
                for (int i : List.of(0, opsPerThread / 2, opsPerThread - 1)) {
                    String key = "thread-" + t + "-key-" + i;
                    assertEquals("Spot-check " + key,
                        Optional.of("val-" + i), e.get(key));
                }
            }

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 11. Concurrent reads — shared read lock does not deadlock
    // -------------------------------------------------------------------------
    static void testConcurrentReads() throws Exception {
        System.out.println("\n\u001B[1m[11] Concurrent Reads\u001B[0m");
        Path wal = tempWal("concurrent-read");
        int numReaders = 32;
        int readsEach  = 1000;

        try (StorageEngine e = new StorageEngine(wal)) {
            // Seed data.
            for (int i = 0; i < 100; i++) {
                e.set("k" + i, "v" + i);
            }

            ExecutorService pool = Executors.newFixedThreadPool(numReaders);
            CountDownLatch latch = new CountDownLatch(numReaders);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int r = 0; r < numReaders; r++) {
                futures.add(pool.submit(() -> {
                    latch.countDown();
                    latch.await();
                    boolean ok = true;
                    Random rng = new Random();
                    for (int i = 0; i < readsEach; i++) {
                        int idx    = rng.nextInt(100);
                        Optional<String> val = e.get("k" + idx);
                        if (!val.isPresent() || !val.get().equals("v" + idx)) {
                            ok = false;
                        }
                    }
                    return ok;
                }));
            }

            boolean allCorrect = true;
            for (Future<Boolean> f : futures) {
                allCorrect &= f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            assertTrue("All concurrent reads returned correct values", allCorrect);

        } finally {
            silentDelete(wal);
        }
    }

    // =========================================================================
    // TTL test methods (12–16)
    // =========================================================================

    // -------------------------------------------------------------------------
    // 12. TTL: key not yet expired — value is still accessible
    // -------------------------------------------------------------------------
    static void testTtlNotYetExpired() throws Exception {
        System.out.println("\n\u001B[1m[12] TTL: Key Not Yet Expired\u001B[0m");
        Path wal = tempWal("ttl-live");
        try (StorageEngine e = new StorageEngine(wal)) {

            e.set("session", "tok-abc", 60L); // 60-second TTL
            assertEquals("GET immediately after SET-with-TTL returns value",
                Optional.of("tok-abc"), e.get("session"));

            long remaining = e.ttl("session");
            assertTrue("TTL returns positive seconds when key is live",
                remaining > 0 && remaining <= 60);

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 13. TTL: key expired on GET — lazy eviction returns empty
    // -------------------------------------------------------------------------
    static void testTtlExpiredOnGet() throws Exception {
        System.out.println("\n\u001B[1m[13] TTL: Key Expired on GET (Lazy Eviction)\u001B[0m");
        Path wal = tempWal("ttl-expired");
        try (StorageEngine e = new StorageEngine(wal)) {

            e.set("shortlived", "value", 1L); // 1-second TTL

            // Value should be accessible immediately.
            assertEquals("GET before expiry returns value",
                Optional.of("value"), e.get("shortlived"));

            // Wait for expiry: 1 second + 200 ms margin.
            sleepMs(1_200);

            assertEquals("GET after TTL expiry returns empty",
                Optional.empty(), e.get("shortlived"));

            long remaining = e.ttl("shortlived");
            assertEquals("TTL returns -2 after expiry", -2L, remaining);

            // Key must not appear in live count after lazy eviction.
            assertEquals("Live key count is 0 after expiry",
                0, e.liveKeyCount());

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 14. TTL: persistence across reopen — WAL X record survives replay
    // -------------------------------------------------------------------------
    static void testTtlPersistenceAcrossReopen() throws Exception {
        System.out.println("\n\u001B[1m[14] TTL: Persistence Across WAL Reopen\u001B[0m");
        Path wal = tempWal("ttl-persist");
        try {
            // Write a key with 10-second TTL and close.
            try (StorageEngine e = new StorageEngine(wal)) {
                e.set("durable-ttl", "hello", 10L);
            }

            // Reopen immediately — key should still be present with remaining TTL.
            try (StorageEngine e = new StorageEngine(wal)) {
                assertEquals("TTL key survives WAL replay",
                    Optional.of("hello"), e.get("durable-ttl"));
                long remaining = e.ttl("durable-ttl");
                assertTrue("Remaining TTL after replay is positive and ≤ 10",
                    remaining > 0 && remaining <= 10);
            }

            // Write a key that will have expired by the time we reopen.
            try (StorageEngine e = new StorageEngine(wal)) {
                e.set("soon-expired", "gone", 1L); // 1 second
                // Verify it is live now.
                assertEquals("soon-expired is live before sleep",
                    Optional.of("gone"), e.get("soon-expired"));
            }

            // Wait for expiry, then reopen — recovery must skip the expired key.
            sleepMs(1_300);
            try (StorageEngine e = new StorageEngine(wal)) {
                assertEquals("Expired key is NOT loaded on WAL replay",
                    Optional.empty(), e.get("soon-expired"));
            }

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 15. TTL: compaction excludes expired keys from the rewritten WAL
    // -------------------------------------------------------------------------
    static void testTtlCompactionExcludesExpired() throws Exception {
        System.out.println("\n\u001B[1m[15] TTL: Compaction Excludes Expired Keys\u001B[0m");
        Path wal = tempWal("ttl-compact");
        try (StorageEngine e = new StorageEngine(wal)) {

            e.set("permanent", "stays");
            e.set("temporary", "leaves", 1L); // 1-second TTL

            long sizeBeforeExpiry = Files.size(wal);

            // Wait for TTL to expire.
            sleepMs(1_300);

            // Compact — must not include the expired key in rewritten WAL.
            e.compact();

            long sizeAfterCompact = Files.size(wal);
            assertTrue("WAL shrinks after compact (expired key removed)",
                sizeAfterCompact < sizeBeforeExpiry);

            assertEquals("Permanent key survives compaction",
                Optional.of("stays"), e.get("permanent"));
            assertEquals("Expired key is absent after compaction",
                Optional.empty(), e.get("temporary"));
            assertEquals("Live key count after TTL-aware compact",
                1, e.liveKeyCount());

        } finally {
            silentDelete(wal);
        }
    }

    // -------------------------------------------------------------------------
    // 16. TTL: overwriting a TTL key with a plain SET clears the TTL
    // -------------------------------------------------------------------------
    static void testTtlOverwriteClearsTtl() throws Exception {
        System.out.println("\n\u001B[1m[16] TTL: Overwrite Clears Previous TTL\u001B[0m");
        Path wal = tempWal("ttl-overwrite");
        try (StorageEngine e = new StorageEngine(wal)) {

            // Set with short TTL.
            e.set("key", "old-value", 2L);
            assertEquals("Value accessible before overwrite",
                Optional.of("old-value"), e.get("key"));

            // Overwrite with no TTL.
            e.set("key", "new-value");
            assertEquals("Overwritten value is correct",
                Optional.of("new-value"), e.get("key"));

            // TTL should be cleared (returns -1 for "no TTL").
            long ttl = e.ttl("key");
            assertEquals("TTL is cleared after plain SET overwrite", -1L, ttl);

            // Wait longer than the original TTL would have allowed.
            sleepMs(2_500);

            // Key should still be accessible — no TTL anymore.
            assertEquals("Key with cleared TTL persists beyond original expiry",
                Optional.of("new-value"), e.get("key"));

        } finally {
            silentDelete(wal);
        }
    }
}
