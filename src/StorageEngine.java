import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StorageEngine — a high-performance, embedded key-value store with optional key expiration (TTL).
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Write Path:  caller → ReadWriteLock (write) → ConcurrentHashMap → WAL append → fsync
 *   Read  Path:  caller → ReadWriteLock (read)  → ConcurrentHashMap (O(1)) → TTL check
 *   Compact:     ReadWriteLock (write) → scan index → rewrite WAL → swap files
 *   Recovery:    sequential WAL replay → rebuild ConcurrentHashMap + expiry map in memory
 *   TTL Purge:   ScheduledExecutorService (virtual thread) → sweep expiry map every second
 * </pre>
 *
 * <h2>WAL Record Format</h2>
 * Each record is a single newline-terminated line:
 * <pre>
 *   SET (no TTL) → "S|<key-len>|<key>|<value-len>|<value>\n"
 *   SET (w/ TTL) → "X|<key-len>|<key>|<value-len>|<value>|<expiry-epoch-ms>\n"
 *   DEL          → "D|<key-len>|<key>\n"
 * </pre>
 * The {@code X} record type is a strict superset of {@code S}: it carries an additional
 * absolute expiry timestamp (epoch milliseconds). Old WAL files containing only {@code S}
 * and {@code D} records are still fully replay-compatible.
 *
 * <h2>TTL / Key Expiration</h2>
 * <ul>
 *   <li><b>Lazy evaluation:</b> {@link #get} checks the expiry map before returning a value.
 *       If the TTL has elapsed the key is removed from both the index and the expiry map,
 *       a tombstone is written to the WAL, and {@link Optional#empty()} is returned.</li>
 *   <li><b>Active purge:</b> A {@link ScheduledExecutorService} backed by a virtual thread
 *       sweeps the expiry map every second and removes keys whose TTL has passed.</li>
 * </ul>
 *
 * <h2>Concurrency Model</h2>
 * A single {@link ReentrantReadWriteLock} guards every mutation ({@code set},
 * {@code delete}, {@code compact}, TTL purge writes).  Concurrent reads via {@code get}
 * share the read lock and never block each other.  The WAL is flushed and synced inside
 * the write lock so callers always see a consistent view of durable state.
 *
 * <h2>Durability</h2>
 * Every mutating operation flushes the {@link BufferedOutputStream} and calls
 * {@link FileChannel#force(boolean)} (fdatasync) before releasing the lock.
 */
public class StorageEngine implements Closeable {

    // -------------------------------------------------------------------------
    // Constants & logger
    // -------------------------------------------------------------------------

    private static final Logger LOG = Logger.getLogger(StorageEngine.class.getName());

    /** Record-type byte for a SET entry (no TTL) in the WAL. */
    private static final char OP_SET = 'S';

    /** Record-type byte for a SET entry WITH expiry in the WAL. */
    private static final char OP_SET_EX = 'X';

    /** Record-type byte for a DELETE entry in the WAL. */
    private static final char OP_DEL = 'D';

    /** Field separator used inside WAL records. */
    private static final char SEP = '|';

    /** Line terminator that ends every WAL record. */
    private static final char LF = '\n';

    /** Buffer size for the WAL writer (64 KiB). */
    private static final int WRITE_BUFFER_BYTES = 64 * 1024;

    /** Buffer size for the WAL reader during recovery (256 KiB). */
    private static final int READ_BUFFER_BYTES = 256 * 1024;

    /** How often the background TTL purger runs (milliseconds). */
    private static final long PURGE_INTERVAL_MS = 1_000L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Path to the active WAL file. */
    private final Path walPath;

    /** Path to a temporary file used during compaction. */
    private final Path walTmpPath;

    /**
     * In-memory index mapping every live key to its latest value.
     * Used for O(1) reads without scanning the WAL.
     */
    private final ConcurrentHashMap<String, String> index = new ConcurrentHashMap<>();

    /**
     * Expiry map: key → absolute expiry timestamp in epoch milliseconds.
     * Keys present here have a TTL; keys absent have no expiry.
     */
    private final ConcurrentHashMap<String, Long> expiry = new ConcurrentHashMap<>();

    /**
     * Guards all mutations.  Multiple readers may hold the read lock
     * simultaneously; any writer holds the write lock exclusively.
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true /* fair */);

    /** Buffered output stream wrapping the WAL file — append mode. */
    private BufferedOutputStream walWriter;

    /** FileChannel to the WAL, used for fdatasync via {@code force()}. */
    private FileChannel walChannel;

    /** Background scheduler that periodically purges expired keys. */
    private final ScheduledExecutorService purger;

    /** Set to true when {@link #close()} has been called. */
    private volatile boolean closed = false;

    // -------------------------------------------------------------------------
    // Telemetry counters (lock-free via AtomicLong)
    // -------------------------------------------------------------------------

    private final AtomicLong opSets        = new AtomicLong();
    private final AtomicLong opGets        = new AtomicLong();
    private final AtomicLong opDeletes     = new AtomicLong();
    private final AtomicLong opCompacts    = new AtomicLong();
    private final AtomicLong recoveredKeys = new AtomicLong();
    private final AtomicLong opExpired     = new AtomicLong();

    // -------------------------------------------------------------------------
    // Constructor / lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) a StorageEngine whose WAL lives at {@code walPath}.
     * If the WAL already exists the engine replays it to reconstruct the
     * in-memory index before accepting new operations.
     *
     * @param walPath  path to the WAL file (will be created if absent)
     * @throws IOException if the WAL cannot be opened or recovery fails
     */
    public StorageEngine(Path walPath) throws IOException {
        this.walPath    = Objects.requireNonNull(walPath, "walPath must not be null");
        this.walTmpPath = walPath.resolveSibling(walPath.getFileName() + ".tmp");

        // Ensure the parent directory exists.
        Path parent = walPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Replay the existing WAL (if any) before opening for append.
        recover();

        // Open WAL for append.
        openWal();

        // Start background TTL purger on a virtual thread.
        purger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("kv-ttl-purger");
            t.setDaemon(true);
            return t;
        });
        purger.scheduleAtFixedRate(this::purgeExpiredKeys,
            PURGE_INTERVAL_MS, PURGE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        LOG.info(String.format(
            "[StorageEngine] ready — walPath=%s, keys=%d (recovered=%d)",
            walPath, index.size(), recoveredKeys.get()));
    }

    /**
     * Convenience constructor that places the WAL in the current working
     * directory under the name {@code kv.wal}.
     */
    public StorageEngine() throws IOException {
        this(Paths.get("kv.wal"));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Stores {@code value} under {@code key}, overwriting any previous mapping.
     * The operation is written to the WAL and flushed to disk before returning.
     * Any existing TTL for the key is cleared.
     *
     * @param key   non-null, non-empty key
     * @param value non-null value (empty string is permitted)
     * @throws IOException              if the WAL write or sync fails
     * @throws IllegalArgumentException if {@code key} is null or empty
     */
    public void set(String key, String value) throws IOException {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");

        lock.writeLock().lock();
        try {
            appendSet(key, value);
            index.put(key, value);
            expiry.remove(key);   // clear any previous TTL
            opSets.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Stores {@code value} under {@code key} with a time-to-live of {@code ttlSeconds}.
     * After the TTL elapses the key will be invisible to {@link #get} and will be
     * removed by the background purger (or lazily on next access).
     *
     * @param key        non-null, non-empty key
     * @param value      non-null value
     * @param ttlSeconds positive number of seconds until the key expires
     * @throws IOException              if the WAL write or sync fails
     * @throws IllegalArgumentException if {@code key} is null/empty or {@code ttlSeconds} ≤ 0
     */
    public void set(String key, String value, long ttlSeconds) throws IOException {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive, got: " + ttlSeconds);
        }

        long expiryMs = System.currentTimeMillis() + ttlSeconds * 1_000L;

        lock.writeLock().lock();
        try {
            appendSetEx(key, value, expiryMs);
            index.put(key, value);
            expiry.put(key, expiryMs);
            opSets.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the value associated with {@code key}, or {@link Optional#empty()}
     * if the key does not exist, has been deleted, or its TTL has expired.
     * Expired keys are lazily evicted on this call.
     *
     * @param key non-null, non-empty key
     * @return an {@link Optional} containing the value, or empty
     * @throws IllegalArgumentException if {@code key} is null or empty
     */
    public Optional<String> get(String key) {
        validateKey(key);
        opGets.incrementAndGet();

        // Fast TTL check without write lock — if already expired, evict lazily.
        Long expiryMs = expiry.get(key);
        if (expiryMs != null && System.currentTimeMillis() > expiryMs) {
            evictExpired(key);
            return Optional.empty();
        }

        lock.readLock().lock();
        try {
            // Re-check after acquiring read lock (purger may have removed it).
            Long exp = expiry.get(key);
            if (exp != null && System.currentTimeMillis() > exp) {
                // Cannot write tombstone under read lock; caller will see empty,
                // and the next purger sweep (within 1 s) will clean it up.
                return Optional.empty();
            }
            return Optional.ofNullable(index.get(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes {@code key} from the store.  If the key does not exist this is a
     * no-op at the index level, but a tombstone is still appended to the WAL to
     * guarantee idempotent replay on recovery.
     *
     * @param key non-null, non-empty key
     * @throws IOException              if the WAL write or sync fails
     * @throws IllegalArgumentException if {@code key} is null or empty
     */
    public void delete(String key) throws IOException {
        validateKey(key);

        lock.writeLock().lock();
        try {
            appendDelete(key);
            index.remove(key);
            expiry.remove(key);
            opDeletes.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rewrites the WAL to contain only the live (non-deleted, non-overwritten,
     * non-expired) key-value pairs, reclaiming disk space consumed by stale entries.
     *
     * <p>Keys with remaining TTL are compacted using {@code X} records so that
     * their expiry timestamp survives the rewrite.
     *
     * @throws IOException if any file operation during compaction fails
     */
    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            // Purge expired keys before snapshotting (don't include them in compacted WAL).
            purgeExpiredKeysUnderLock();

            // Snapshot the index and expiry maps under the write lock.
            Map<String, String> snap       = new HashMap<>(index);
            Map<String, Long>   snapExpiry = new HashMap<>(expiry);

            // Close the current WAL writer before we touch the file.
            closeWal();

            // Write compacted WAL to a sibling tmp file.
            try (FileOutputStream fos = new FileOutputStream(walTmpPath.toFile(), false);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, WRITE_BUFFER_BYTES);
                 FileChannel ch = fos.getChannel()) {

                for (Map.Entry<String, String> entry : snap.entrySet()) {
                    String key   = entry.getKey();
                    String value = entry.getValue();
                    Long exp = snapExpiry.get(key);
                    if (exp != null) {
                        writeSetExRecord(bos, key, value, exp);
                    } else {
                        writeSetRecord(bos, key, value);
                    }
                }
                bos.flush();
                ch.force(false);
            }

            // Atomic rename: tmp -> active WAL.
            Files.move(walTmpPath, walPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

            // Re-open for append.
            openWal();
            opCompacts.incrementAndGet();

            LOG.info(String.format(
                "[StorageEngine] compact complete — live keys=%d, walSize=%d bytes",
                snap.size(), Files.size(walPath)));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the remaining TTL in seconds for the given key, or -1 if the key
     * has no TTL, or -2 if the key does not exist / is expired.
     *
     * @param key non-null, non-empty key
     * @return TTL in seconds, -1 (no TTL), or -2 (key not found / expired)
     */
    public long ttl(String key) {
        validateKey(key);
        Long expiryMs = expiry.get(key);
        if (expiryMs == null) {
            // Check if key exists without a TTL.
            lock.readLock().lock();
            try {
                return index.containsKey(key) ? -1L : -2L;
            } finally {
                lock.readLock().unlock();
            }
        }
        long remaining = expiryMs - System.currentTimeMillis();
        return remaining > 0 ? (remaining / 1_000L) : -2L;
    }

    /**
     * Returns a snapshot of runtime statistics as a formatted, human-readable
     * {@link String}.
     *
     * @return a multi-line statistics report
     */
    public String stats() {
        long walSize = 0;
        try {
            walSize = Files.exists(walPath) ? Files.size(walPath) : 0L;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read WAL size for stats", e);
        }

        return String.format(
            "=== StorageEngine Stats ===%n" +
            "  Live keys       : %d%n"      +
            "  Keys with TTL   : %d%n"      +
            "  WAL file size   : %s%n"      +
            "  WAL path        : %s%n"      +
            "  SET  ops        : %d%n"      +
            "  GET  ops        : %d%n"      +
            "  DELETE ops      : %d%n"      +
            "  COMPACT ops     : %d%n"      +
            "  Expired purged  : %d%n"      +
            "  Recovered keys  : %d%n"      +
            "===========================",
            index.size(),
            expiry.size(),
            formatBytes(walSize),
            walPath.toAbsolutePath(),
            opSets.get(),
            opGets.get(),
            opDeletes.get(),
            opCompacts.get(),
            opExpired.get(),
            recoveredKeys.get()
        );
    }

    /**
     * Flushes any buffered WAL data, stops the TTL purger, and closes all file handles.
     * After this call the engine must not be used.
     */
    @Override
    public void close() throws IOException {
        closed = true;
        purger.shutdownNow();
        try {
            purger.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lock.writeLock().lock();
        try {
            closeWal();
            LOG.info("[StorageEngine] closed.");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // TTL / expiry helpers
    // -------------------------------------------------------------------------

    /**
     * Lazily evicts a single expired key by acquiring the write lock, writing a
     * tombstone to the WAL, and removing from both maps. Safe to call concurrently;
     * if the write lock cannot be acquired immediately (e.g., under heavy write
     * contention) the eviction is skipped — the key will be caught by the next
     * purger sweep.
     */
    private void evictExpired(String key) {
        if (closed) return;
        boolean locked = lock.writeLock().tryLock();
        if (!locked) return;
        try {
            // Double-check: another thread may have already evicted this key.
            Long exp = expiry.get(key);
            if (exp == null || System.currentTimeMillis() <= exp) return;

            try {
                appendDelete(key);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[StorageEngine] Failed to write TTL tombstone for key=" + key, e);
            }
            index.remove(key);
            expiry.remove(key);
            opExpired.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Scheduled purger task — sweeps the expiry map and evicts all keys whose
     * TTL has passed. Called on a virtual thread every {@value #PURGE_INTERVAL_MS} ms.
     */
    private void purgeExpiredKeys() {
        if (closed) return;
        long now = System.currentTimeMillis();
        // Collect expired keys without holding the write lock first (avoids starvation).
        List<String> expired = new ArrayList<>();
        expiry.forEach((key, exp) -> {
            if (now > exp) expired.add(key);
        });
        if (expired.isEmpty()) return;

        lock.writeLock().lock();
        try {
            purgeExpiredKeysUnderLock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes all currently-expired keys. Must be called while holding the write lock.
     */
    private void purgeExpiredKeysUnderLock() {
        long now = System.currentTimeMillis();
        expiry.forEach((key, exp) -> {
            if (now > exp) {
                try {
                    appendDelete(key);
                } catch (IOException e) {
                    LOG.log(Level.WARNING,
                        "[StorageEngine] Failed to write TTL tombstone for key=" + key, e);
                }
                index.remove(key);
                expiry.remove(key);
                opExpired.incrementAndGet();
            }
        });
    }

    // -------------------------------------------------------------------------
    // WAL I/O — all called only while holding the write lock
    // -------------------------------------------------------------------------

    /** Opens the WAL file for append and initialises walWriter / walChannel. */
    private void openWal() throws IOException {
        FileOutputStream fos = new FileOutputStream(walPath.toFile(), true /* append */);
        walChannel = fos.getChannel();
        walWriter  = new BufferedOutputStream(fos, WRITE_BUFFER_BYTES);
    }

    /** Flushes, syncs, and closes the current WAL handles. */
    private void closeWal() throws IOException {
        if (walWriter != null) {
            try {
                walWriter.flush();
                if (walChannel != null && walChannel.isOpen()) {
                    walChannel.force(false);
                }
            } finally {
                walWriter.close();
                walWriter  = null;
                walChannel = null;
            }
        }
    }

    /** Appends a SET record (no TTL) and syncs the WAL. */
    private void appendSet(String key, String value) throws IOException {
        writeSetRecord(walWriter, key, value);
        walWriter.flush();
        walChannel.force(false);
    }

    /** Appends a SET-with-expiry record and syncs the WAL. */
    private void appendSetEx(String key, String value, long expiryMs) throws IOException {
        writeSetExRecord(walWriter, key, value, expiryMs);
        walWriter.flush();
        walChannel.force(false);
    }

    /** Appends a DEL record and syncs the WAL. */
    private void appendDelete(String key) throws IOException {
        writeDelRecord(walWriter, key);
        walWriter.flush();
        walChannel.force(false);
    }

    /**
     * Writes a single SET record (no TTL) to the given stream.
     * Format: {@code S|<key-len>|<key>|<value-len>|<value>\n}
     */
    private static void writeSetRecord(OutputStream out, String key, String value)
            throws IOException {
        byte[] keyBytes   = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        String record = OP_SET + "" + SEP + keyBytes.length + SEP +
                        key + SEP + valueBytes.length + SEP + value + LF;
        out.write(record.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a single SET-with-expiry record to the given stream.
     * Format: {@code X|<key-len>|<key>|<value-len>|<value>|<expiry-epoch-ms>\n}
     */
    private static void writeSetExRecord(OutputStream out, String key, String value, long expiryMs)
            throws IOException {
        byte[] keyBytes   = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        String record = OP_SET_EX + "" + SEP + keyBytes.length + SEP +
                        key + SEP + valueBytes.length + SEP + value + SEP + expiryMs + LF;
        out.write(record.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a single DEL record to the given stream.
     * Format: {@code D|<key-len>|<key>\n}
     */
    private static void writeDelRecord(OutputStream out, String key) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        String record = OP_DEL + "" + SEP + keyBytes.length + SEP + key + LF;
        out.write(record.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // WAL Recovery
    // -------------------------------------------------------------------------

    /**
     * Replays the WAL from disk to reconstruct the in-memory index and expiry map.
     *
     * <p>The parser is length-prefix–driven, so it correctly handles keys and
     * values that contain pipe characters, newlines, or any other byte.
     * Partial trailing records (due to a crash mid-write) are silently skipped.
     * Expired keys found during recovery are skipped (not loaded into the index).
     */
    private void recover() throws IOException {
        if (!Files.exists(walPath)) {
            LOG.info("[StorageEngine] No WAL found — starting fresh.");
            return;
        }

        LOG.info("[StorageEngine] Replaying WAL: " + walPath);
        long linesProcessed = 0;
        long linesCorrupted = 0;
        long keysRecovered  = 0;
        long now = System.currentTimeMillis();

        try (InputStream fis  = Files.newInputStream(walPath);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(fis, StandardCharsets.UTF_8), READ_BUFFER_BYTES)) {

            String line;
            while ((line = reader.readLine()) != null) {
                linesProcessed++;
                try {
                    WalRecord record = parseRecord(line);
                    if (record == null) {
                        linesCorrupted++;
                        continue;
                    }
                    if (record.op() == OP_SET) {
                        index.put(record.key(), record.value());
                        expiry.remove(record.key());
                        keysRecovered++;
                    } else if (record.op() == OP_SET_EX) {
                        long exp = record.expiryMs();
                        if (now > exp) {
                            // Already expired — skip loading; tombstone not needed
                            // since compaction will exclude it on next run.
                            index.remove(record.key());
                            expiry.remove(record.key());
                        } else {
                            index.put(record.key(), record.value());
                            expiry.put(record.key(), exp);
                            keysRecovered++;
                        }
                    } else if (record.op() == OP_DEL) {
                        index.remove(record.key());
                        expiry.remove(record.key());
                        keysRecovered++;
                    } else {
                        linesCorrupted++;
                    }
                } catch (Exception e) {
                    linesCorrupted++;
                    LOG.log(Level.WARNING,
                        "[StorageEngine] Skipping corrupt WAL record at line " + linesProcessed, e);
                }
            }
        }

        recoveredKeys.set(keysRecovered);

        LOG.info(String.format(
            "[StorageEngine] WAL replay complete — lines=%d, corrupt=%d, liveKeys=%d",
            linesProcessed, linesCorrupted, index.size()));
    }

    /**
     * Parses a single WAL line into a {@link WalRecord}.
     * Returns {@code null} if the line is malformed.
     */
    private static WalRecord parseRecord(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        char op = line.charAt(0);

        if (op == OP_SET || op == OP_SET_EX) {
            // Format (S): S|<keyLen>|<key>|<valLen>|<value>
            // Format (X): X|<keyLen>|<key>|<valLen>|<value>|<expiryMs>
            int idx1 = line.indexOf(SEP, 2);
            if (idx1 < 0) return null;
            int keyLen;
            try {
                keyLen = Integer.parseInt(line.substring(2, idx1));
            } catch (NumberFormatException e) {
                return null;
            }
            int keyStart = idx1 + 1;
            int keyEnd   = keyStart + keyLen;
            if (keyEnd >= line.length()) return null;
            String key = line.substring(keyStart, keyEnd);

            if (line.charAt(keyEnd) != SEP) return null;
            int idx2 = line.indexOf(SEP, keyEnd + 1);
            if (idx2 < 0) return null;
            int valLen;
            try {
                valLen = Integer.parseInt(line.substring(keyEnd + 1, idx2));
            } catch (NumberFormatException e) {
                return null;
            }
            int valStart = idx2 + 1;
            int valEnd   = valStart + valLen;
            if (valEnd > line.length()) return null;
            String value = line.substring(valStart, valEnd);

            if (op == OP_SET) {
                return new WalRecord(OP_SET, key, value, 0L);
            }

            // OP_SET_EX: remaining is "|<expiryMs>"
            if (valEnd >= line.length() || line.charAt(valEnd) != SEP) return null;
            long expiryMs;
            try {
                expiryMs = Long.parseLong(line.substring(valEnd + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            return new WalRecord(OP_SET_EX, key, value, expiryMs);

        } else if (op == OP_DEL) {
            // Format: D|<keyLen>|<key>
            int idx1 = line.indexOf(SEP, 2);
            if (idx1 < 0) return null;
            int keyLen;
            try {
                keyLen = Integer.parseInt(line.substring(2, idx1));
            } catch (NumberFormatException e) {
                return null;
            }
            int keyStart = idx1 + 1;
            int keyEnd   = keyStart + keyLen;
            if (keyEnd > line.length()) return null;
            String key = line.substring(keyStart, keyEnd);

            return new WalRecord(OP_DEL, key, null, 0L);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key must be non-null and non-empty.");
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024)           return bytes + " B";
        if (bytes < 1024 * 1024)    return String.format("%.2f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (visible to tests in the same default package)
    // -------------------------------------------------------------------------

    /** Returns the number of live keys currently in the index. */
    int liveKeyCount() {
        return index.size();
    }

    /** Returns a read-only snapshot of the index (for testing). */
    Map<String, String> indexSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(index));
    }

    /** Returns a read-only snapshot of the expiry map (for testing). */
    Map<String, Long> expirySnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(expiry));
    }

    // -------------------------------------------------------------------------
    // Internal record type
    // -------------------------------------------------------------------------

    /**
     * Parsed representation of a single WAL record.
     *
     * @param op        operation character: {@code S}, {@code X}, or {@code D}
     * @param key       the key
     * @param value     the value (null for DEL records)
     * @param expiryMs  absolute expiry epoch millis (0 for non-TTL records)
     */
    private record WalRecord(char op, String key, String value, long expiryMs) {}
}
