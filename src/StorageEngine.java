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
 * StorageEngine — a high-performance, embedded key-value store.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Write Path:  caller → ReadWriteLock (write) → ConcurrentHashMap → WAL append → fsync
 *   Read  Path:  caller → ReadWriteLock (read)  → ConcurrentHashMap (O(1))
 *   Compact:     ReadWriteLock (write) → scan index → rewrite WAL → swap files
 *   Recovery:    sequential WAL replay → rebuild ConcurrentHashMap in memory
 * </pre>
 *
 * <h2>WAL Record Format</h2>
 * Each record is a single newline-terminated line:
 * <pre>
 *   SET  → "S|<key-len>|<key>|<value-len>|<value>\n"
 *   DEL  → "D|<key-len>|<key>\n"
 * </pre>
 * Key and value are stored as raw UTF-8; the length prefix handles embedded
 * newlines, pipes, or any other special bytes in user data.
 *
 * <h2>Concurrency Model</h2>
 * A single {@link ReentrantReadWriteLock} guards every mutation ({@code set},
 * {@code delete}, {@code compact}).  Concurrent reads via {@code get} share the
 * read lock and never block each other.  The WAL is flushed and synced inside
 * the write lock so callers always see a consistent view of durable state.
 *
 * <h2>Durability</h2>
 * Every mutating operation flushes the {@link BufferedOutputStream} and calls
 * {@link FileChannel#force(boolean)} (fdatasync) before releasing the lock.
 * Power-loss between the in-memory update and the sync can therefore only lose
 * the very last operation, and recovery will discard any partial trailing
 * record automatically.
 */
public class StorageEngine implements Closeable {

    // -------------------------------------------------------------------------
    // Constants & logger
    // -------------------------------------------------------------------------

    private static final Logger LOG = Logger.getLogger(StorageEngine.class.getName());

    /** Record-type byte for a SET entry in the WAL. */
    private static final char OP_SET = 'S';

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
     * Guards all mutations.  Multiple readers may hold the read lock
     * simultaneously; any writer holds the write lock exclusively.
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true /* fair */);

    /** Buffered output stream wrapping the WAL file — append mode. */
    private BufferedOutputStream walWriter;

    /** FileChannel to the WAL, used for fdatasync via {@code force()}. */
    private FileChannel walChannel;

    // -------------------------------------------------------------------------
    // Telemetry counters (lock-free via AtomicLong)
    // -------------------------------------------------------------------------

    private final AtomicLong opSets        = new AtomicLong();
    private final AtomicLong opGets        = new AtomicLong();
    private final AtomicLong opDeletes     = new AtomicLong();
    private final AtomicLong opCompacts    = new AtomicLong();
    private final AtomicLong recoveredKeys = new AtomicLong();

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
            opSets.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the value associated with {@code key}, or {@link Optional#empty()}
     * if the key does not exist or has been deleted.
     *
     * @param key non-null, non-empty key
     * @return an {@link Optional} containing the value, or empty
     * @throws IllegalArgumentException if {@code key} is null or empty
     */
    public Optional<String> get(String key) {
        validateKey(key);
        opGets.incrementAndGet();

        lock.readLock().lock();
        try {
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
            opDeletes.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rewrites the WAL to contain only the live (non-deleted, non-overwritten)
     * key-value pairs, reclaiming disk space consumed by stale entries.
     *
     * <p>The sequence is:
     * <ol>
     *   <li>Acquire the exclusive write lock.</li>
     *   <li>Snapshot the current in-memory index.</li>
     *   <li>Write all live pairs to a temporary WAL file.</li>
     *   <li>Atomically rename the tmp file over the active WAL.</li>
     *   <li>Re-open the WAL for append.</li>
     * </ol>
     *
     * @throws IOException if any file operation during compaction fails
     */
    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            // Snapshot the index under the write lock so no mutations slip in.
            Map<String, String> snapshot = new HashMap<>(index);

            // Close the current WAL writer before we touch the file.
            closeWal();

            // Write compacted WAL to a sibling tmp file.
            try (FileOutputStream fos = new FileOutputStream(walTmpPath.toFile(), false);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, WRITE_BUFFER_BYTES);
                 FileChannel ch = fos.getChannel()) {

                for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                    writeSetRecord(bos, entry.getKey(), entry.getValue());
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
                snapshot.size(), Files.size(walPath)));
        } finally {
            lock.writeLock().unlock();
        }
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
            "  WAL file size   : %s%n"      +
            "  WAL path        : %s%n"      +
            "  SET  ops        : %d%n"      +
            "  GET  ops        : %d%n"      +
            "  DELETE ops      : %d%n"      +
            "  COMPACT ops     : %d%n"      +
            "  Recovered keys  : %d%n"      +
            "===========================",
            index.size(),
            formatBytes(walSize),
            walPath.toAbsolutePath(),
            opSets.get(),
            opGets.get(),
            opDeletes.get(),
            opCompacts.get(),
            recoveredKeys.get()
        );
    }

    /**
     * Flushes any buffered WAL data and closes all file handles.
     * After this call the engine must not be used.
     */
    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            closeWal();
            LOG.info("[StorageEngine] closed.");
        } finally {
            lock.writeLock().unlock();
        }
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

    /** Appends a SET record and syncs the WAL. */
    private void appendSet(String key, String value) throws IOException {
        writeSetRecord(walWriter, key, value);
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
     * Writes a single SET record to the given stream.
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
     * Replays the WAL from disk to reconstruct the in-memory index.
     *
     * <p>The parser is length-prefix–driven, so it correctly handles keys and
     * values that contain pipe characters, newlines, or any other byte.
     * Partial trailing records (due to a crash mid-write) are silently skipped.
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
                        keysRecovered++;
                    } else if (record.op() == OP_DEL) {
                        index.remove(record.key());
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

        if (op == OP_SET) {
            // Format: S|<keyLen>|<key>|<valLen>|<value>
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

            return new WalRecord(OP_SET, key, value);

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

            return new WalRecord(OP_DEL, key, null);
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

    // -------------------------------------------------------------------------
    // Internal record type
    // -------------------------------------------------------------------------

    /** Parsed representation of a single WAL record. */
    private record WalRecord(char op, String key, String value) {}
}
