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
 * StorageEngine — a high-performance, embedded key-value store with optional key expiration (TTL)
 * and support for three value types: Strings, Lists (via {@link CopyOnWriteArrayList}), and Sets
 * (via {@link ConcurrentHashMap#newKeySet()}).
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Write Path:  caller → ReadWriteLock (write) → ConcurrentHashMap → WAL append → fsync
 *   Read  Path:  caller → ReadWriteLock (read)  → ConcurrentHashMap (O(1)) → type check
 *   Compact:     ReadWriteLock (write) → scan index → rewrite WAL → swap files
 *   Recovery:    sequential WAL replay → rebuild index + expiry map in memory
 *   TTL Purge:   ScheduledExecutorService (virtual thread) → sweep expiry map every second
 * </pre>
 *
 * <h2>WAL Record Format</h2>
 * Each record is a single newline-terminated line:
 * <pre>
 *   SET (no TTL) → "S|&lt;key-len&gt;|&lt;key&gt;|&lt;value-len&gt;|&lt;value&gt;\n"
 *   SET (w/ TTL) → "X|&lt;key-len&gt;|&lt;key&gt;|&lt;value-len&gt;|&lt;value&gt;|&lt;expiry-epoch-ms&gt;\n"
 *   DEL          → "D|&lt;key-len&gt;|&lt;key&gt;\n"
 *   LPUSH        → "L|&lt;key-len&gt;|&lt;key&gt;|&lt;elem-len&gt;|&lt;element&gt;\n"
 *   SADD         → "A|&lt;key-len&gt;|&lt;key&gt;|&lt;elem-len&gt;|&lt;element&gt;\n"
 * </pre>
 * The {@code L} and {@code A} record types share the same two-field length-prefix
 * format as {@code S}, making the parser generic. Old WAL files with only {@code S},
 * {@code X}, and {@code D} records are fully replay-compatible.
 *
 * <h2>Value Types</h2>
 * <ul>
 *   <li><b>String</b> — stored as {@link String}. Supports: SET, GET, TTL, DELETE.</li>
 *   <li><b>List</b>   — stored as {@link CopyOnWriteArrayList}{@code <String>}.
 *       Supports: LPUSH (prepend), LRANGE (slice), DELETE.</li>
 *   <li><b>Set</b>    — stored as a concurrent key-set from
 *       {@link ConcurrentHashMap#newKeySet()}. Supports: SADD, SMEMBERS, DELETE.</li>
 * </ul>
 * Operations against the wrong type throw {@link IllegalStateException} with the message
 * {@value #WRONGTYPE_ERR}. A plain SET always overwrites any existing type, consistent
 * with Redis semantics.
 *
 * <h2>Compaction and Lists/Sets</h2>
 * During compaction:
 * <ul>
 *   <li>Lists are replayed from tail to head so that sequential LPUSH records
 *       reconstruct the correct front-first ordering.</li>
 *   <li>Sets are written one SADD record per member (order is arbitrary since
 *       sets are unordered).</li>
 * </ul>
 */
public class StorageEngine implements Closeable {

    // -------------------------------------------------------------------------
    // Constants & logger
    // -------------------------------------------------------------------------

    private static final Logger LOG = Logger.getLogger(StorageEngine.class.getName());

    /** WAL opcode: SET entry (no TTL). */
    private static final char OP_SET    = 'S';
    /** WAL opcode: SET entry with expiry timestamp. */
    private static final char OP_SET_EX = 'X';
    /** WAL opcode: DELETE entry. */
    private static final char OP_DEL    = 'D';
    /** WAL opcode: LPUSH — prepend one element to a list. */
    private static final char OP_LPUSH  = 'L';
    /** WAL opcode: SADD  — add one element to a set. */
    private static final char OP_SADD   = 'A';

    /** Error message returned when a type-mismatched command is attempted. */
    static final String WRONGTYPE_ERR =
        "WRONGTYPE operation against a key holding the wrong kind of value";

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

    private final Path walPath;
    private final Path walTmpPath;

    /**
     * In-memory index. Values are one of:
     * <ul>
     *   <li>{@link String}                      — for string keys</li>
     *   <li>{@link CopyOnWriteArrayList}{@code <String>} — for list keys (LPUSH)</li>
     *   <li>{@link Set}{@code <String>}          — for set keys (SADD)</li>
     * </ul>
     */
    private final ConcurrentHashMap<String, Object> index = new ConcurrentHashMap<>();

    /**
     * Expiry map: key → absolute expiry timestamp in epoch milliseconds.
     * TTL only applies to string-typed keys.
     */
    private final ConcurrentHashMap<String, Long> expiry = new ConcurrentHashMap<>();

    /**
     * Guards all mutations.  Concurrent reads share the read lock; any writer
     * holds the write lock exclusively.
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

        Path parent = walPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        recover();
        openWal();

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

    /** Convenience constructor that uses {@code kv.wal} in the current directory. */
    public StorageEngine() throws IOException {
        this(Paths.get("kv.wal"));
    }

    // -------------------------------------------------------------------------
    // Public API — Strings
    // -------------------------------------------------------------------------

    /**
     * Stores {@code value} under {@code key}, overwriting any previous mapping
     * regardless of its type (String, List, or Set). Any existing TTL is cleared.
     * The operation is written to the WAL and flushed to disk before returning.
     *
     * @throws IOException              if the WAL write or sync fails
     * @throws IllegalArgumentException if {@code key} is null/empty
     */
    public void set(String key, String value) throws IOException {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");
        lock.writeLock().lock();
        try {
            appendSet(key, value);
            index.put(key, value);
            expiry.remove(key);
            opSets.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Stores {@code value} under {@code key} with a time-to-live of
     * {@code ttlSeconds}. Overwrites any previous mapping of any type.
     *
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
     * Returns the string value for {@code key}, or {@link Optional#empty()} if
     * the key is absent, expired, or holds a non-string type (for non-string keys
     * use {@link #lrange} or {@link #smembers}).
     *
     * @throws IllegalArgumentException if {@code key} is null/empty
     * @throws IllegalStateException    if the key holds a List or Set value
     */
    public Optional<String> get(String key) {
        validateKey(key);
        opGets.incrementAndGet();

        // Fast TTL check before acquiring the read lock.
        Long expiryMs = expiry.get(key);
        if (expiryMs != null && System.currentTimeMillis() > expiryMs) {
            evictExpired(key);
            return Optional.empty();
        }

        lock.readLock().lock();
        try {
            Long exp = expiry.get(key);
            if (exp != null && System.currentTimeMillis() > exp) {
                return Optional.empty();
            }
            Object val = index.get(key);
            if (val == null) return Optional.empty();
            if (!(val instanceof String)) {
                throw new IllegalStateException(WRONGTYPE_ERR);
            }
            return Optional.of((String) val);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes {@code key} from the store regardless of its type.
     * A tombstone is always written to the WAL even if the key does not exist.
     *
     * @throws IOException              if the WAL write or sync fails
     * @throws IllegalArgumentException if {@code key} is null/empty
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

    // -------------------------------------------------------------------------
    // Public API — Lists
    // -------------------------------------------------------------------------

    /**
     * Prepends {@code element} to the list stored at {@code key}.
     * If the key does not exist a new list is created. If the key exists
     * and holds a non-list type, an {@link IllegalStateException} is thrown.
     *
     * <p>After the operation the element is at index 0 of the list.
     *
     * @return the new length of the list after the push
     * @throws IOException           if the WAL write or sync fails
     * @throws IllegalStateException if the key holds a String or Set value
     */
    @SuppressWarnings("unchecked")
    public int lpush(String key, String element) throws IOException {
        validateKey(key);
        Objects.requireNonNull(element, "element must not be null");
        lock.writeLock().lock();
        try {
            Object existing = index.get(key);
            if (existing != null && !(existing instanceof CopyOnWriteArrayList)) {
                throw new IllegalStateException(WRONGTYPE_ERR);
            }
            CopyOnWriteArrayList<String> list =
                (existing == null) ? new CopyOnWriteArrayList<>()
                                   : (CopyOnWriteArrayList<String>) existing;
            list.add(0, element);          // prepend
            index.put(key, list);
            appendLpush(key, element);
            opSets.incrementAndGet();
            return list.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns a sub-list from {@code start} (inclusive) to {@code end}
     * (inclusive) of the list stored at {@code key}.
     *
     * <p>Negative indices count from the tail: -1 is the last element,
     * -2 is the second-to-last, etc. An out-of-range range returns an empty list.
     *
     * @return an immutable copy of the requested range (never null)
     * @throws IllegalStateException if the key holds a String or Set value
     */
    @SuppressWarnings("unchecked")
    public List<String> lrange(String key, int start, int end) {
        validateKey(key);
        lock.readLock().lock();
        try {
            Object val = index.get(key);
            if (val == null) return List.of();
            if (!(val instanceof List)) throw new IllegalStateException(WRONGTYPE_ERR);
            List<String> list = (List<String>) val;
            int size = list.size();
            // Resolve negative indices.
            if (start < 0) start = Math.max(0, size + start);
            if (end   < 0) end   = size + end;
            if (start > end || start >= size || end < 0) return List.of();
            end = Math.min(end, size - 1);
            return List.copyOf(list.subList(start, end + 1));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the number of elements in the list at {@code key}, or 0 if absent. */
    @SuppressWarnings("unchecked")
    public int llen(String key) {
        validateKey(key);
        lock.readLock().lock();
        try {
            Object val = index.get(key);
            if (val == null) return 0;
            if (!(val instanceof List)) throw new IllegalStateException(WRONGTYPE_ERR);
            return ((List<String>) val).size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Public API — Sets
    // -------------------------------------------------------------------------

    /**
     * Adds {@code member} to the set stored at {@code key}.
     * If the key does not exist a new set is created. If the key exists and
     * holds a non-set type, an {@link IllegalStateException} is thrown.
     *
     * @return {@code true} if the member was newly added, {@code false} if
     *         it was already a member
     * @throws IOException           if the WAL write or sync fails
     * @throws IllegalStateException if the key holds a String or List value
     */
    @SuppressWarnings("unchecked")
    public boolean sadd(String key, String member) throws IOException {
        validateKey(key);
        Objects.requireNonNull(member, "member must not be null");
        lock.writeLock().lock();
        try {
            Object existing = index.get(key);
            if (existing != null && !(existing instanceof Set)) {
                throw new IllegalStateException(WRONGTYPE_ERR);
            }
            Set<String> set = (existing == null)
                ? ConcurrentHashMap.newKeySet()
                : (Set<String>) existing;
            boolean added = set.add(member);
            if (existing == null) index.put(key, set);
            if (added) {
                appendSadd(key, member);
                opSets.incrementAndGet();
            }
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns all members of the set stored at {@code key}.
     *
     * @return an immutable copy of the set (never null; empty if key is absent)
     * @throws IllegalStateException if the key holds a String or List value
     */
    @SuppressWarnings("unchecked")
    public Set<String> smembers(String key) {
        validateKey(key);
        lock.readLock().lock();
        try {
            Object val = index.get(key);
            if (val == null) return Set.of();
            if (!(val instanceof Set)) throw new IllegalStateException(WRONGTYPE_ERR);
            return Set.copyOf((Set<String>) val);
        } finally {
            lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Public API — Type inspection
    // -------------------------------------------------------------------------

    /**
     * Returns the type of the value stored at {@code key}.
     *
     * @return {@code "string"}, {@code "list"}, {@code "set"}, or {@code "none"}
     */
    public String type(String key) {
        validateKey(key);
        // TTL check first.
        Long exp = expiry.get(key);
        if (exp != null && System.currentTimeMillis() > exp) return "none";
        Object val = index.get(key);
        if (val == null)                        return "none";
        if (val instanceof String)              return "string";
        if (val instanceof List)                return "list";
        if (val instanceof Set)                 return "set";
        return "unknown";
    }

    // -------------------------------------------------------------------------
    // Public API — TTL, Compaction, Stats, Close
    // -------------------------------------------------------------------------

    /**
     * Returns the remaining TTL in seconds for the given key, or -1 if the
     * key has no TTL, or -2 if the key does not exist / is expired.
     * TTL only applies to string-typed keys.
     */
    public long ttl(String key) {
        validateKey(key);
        Long expiryMs = expiry.get(key);
        if (expiryMs == null) {
            lock.readLock().lock();
            try { return index.containsKey(key) ? -1L : -2L; }
            finally { lock.readLock().unlock(); }
        }
        long remaining = expiryMs - System.currentTimeMillis();
        return remaining > 0 ? (remaining / 1_000L) : -2L;
    }

    /**
     * Rewrites the WAL to contain only live entries, reclaiming disk space
     * consumed by stale, overwritten, and expired records.
     *
     * <p>All three value types are handled: string values are written as {@code S}
     * or {@code X} records; list elements are written in tail-to-head order as
     * sequential {@code L} (LPUSH) records so that replay reconstructs the
     * correct front-first ordering; set members are written as {@code A} records.
     *
     * @throws IOException if any file operation during compaction fails
     */
    @SuppressWarnings("unchecked")
    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            purgeExpiredKeysUnderLock();

            // Snapshot under write lock.
            Map<String, Object> snap      = new HashMap<>(index);
            Map<String, Long>   snapExpiry = new HashMap<>(expiry);

            closeWal();

            try (FileOutputStream fos = new FileOutputStream(walTmpPath.toFile(), false);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, WRITE_BUFFER_BYTES);
                 FileChannel ch = fos.getChannel()) {

                for (Map.Entry<String, Object> entry : snap.entrySet()) {
                    String key = entry.getKey();
                    Object val = entry.getValue();

                    if (val instanceof String sv) {
                        Long exp = snapExpiry.get(key);
                        if (exp != null) {
                            writeSetExRecord(bos, key, sv, exp);
                        } else {
                            writeSetRecord(bos, key, sv);
                        }
                    } else if (val instanceof List) {
                        // Write tail-to-head so LPUSH replay rebuilds front-first order.
                        List<String> list = (List<String>) val;
                        for (int i = list.size() - 1; i >= 0; i--) {
                            writeLpushRecord(bos, key, list.get(i));
                        }
                    } else if (val instanceof Set) {
                        for (String member : (Set<String>) val) {
                            writeSaddRecord(bos, key, member);
                        }
                    }
                }
                bos.flush();
                ch.force(false);
            }

            Files.move(walTmpPath, walPath,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
     * Returns a formatted, human-readable statistics report.
     */
    public String stats() {
        long walSize = 0;
        try {
            walSize = Files.exists(walPath) ? Files.size(walPath) : 0L;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read WAL size for stats", e);
        }
        long lists = index.values().stream().filter(v -> v instanceof List).count();
        long sets  = index.values().stream().filter(v -> v instanceof Set).count();

        return String.format(
            "=== StorageEngine Stats ===%n" +
            "  Live keys       : %d%n"      +
            "  String keys     : %d%n"      +
            "  List keys       : %d%n"      +
            "  Set keys        : %d%n"      +
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
            index.size() - lists - sets,
            lists,
            sets,
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
     */
    @Override
    public void close() throws IOException {
        closed = true;
        purger.shutdownNow();
        try { purger.awaitTermination(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    private void evictExpired(String key) {
        if (closed) return;
        boolean locked = lock.writeLock().tryLock();
        if (!locked) return;
        try {
            Long exp = expiry.get(key);
            if (exp == null || System.currentTimeMillis() <= exp) return;
            try { appendDelete(key); } catch (IOException e) {
                LOG.log(Level.WARNING, "[StorageEngine] Failed to write TTL tombstone for key=" + key, e);
            }
            index.remove(key);
            expiry.remove(key);
            opExpired.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void purgeExpiredKeys() {
        if (closed) return;
        long now = System.currentTimeMillis();
        boolean anyExpired = false;
        for (Map.Entry<String, Long> e : expiry.entrySet()) {
            if (now > e.getValue()) { anyExpired = true; break; }
        }
        if (!anyExpired) return;
        lock.writeLock().lock();
        try { purgeExpiredKeysUnderLock(); }
        finally { lock.writeLock().unlock(); }
    }

    private void purgeExpiredKeysUnderLock() {
        long now = System.currentTimeMillis();
        expiry.forEach((key, exp) -> {
            if (now > exp) {
                try { appendDelete(key); } catch (IOException e) {
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
    // WAL I/O — called only while holding the write lock
    // -------------------------------------------------------------------------

    private void openWal() throws IOException {
        FileOutputStream fos = new FileOutputStream(walPath.toFile(), true);
        walChannel = fos.getChannel();
        walWriter  = new BufferedOutputStream(fos, WRITE_BUFFER_BYTES);
    }

    private void closeWal() throws IOException {
        if (walWriter != null) {
            try {
                walWriter.flush();
                if (walChannel != null && walChannel.isOpen()) walChannel.force(false);
            } finally {
                walWriter.close();
                walWriter  = null;
                walChannel = null;
            }
        }
    }

    private void appendSet(String key, String value) throws IOException {
        writeSetRecord(walWriter, key, value);
        walWriter.flush(); walChannel.force(false);
    }

    private void appendSetEx(String key, String value, long expiryMs) throws IOException {
        writeSetExRecord(walWriter, key, value, expiryMs);
        walWriter.flush(); walChannel.force(false);
    }

    private void appendDelete(String key) throws IOException {
        writeDelRecord(walWriter, key);
        walWriter.flush(); walChannel.force(false);
    }

    private void appendLpush(String key, String element) throws IOException {
        writeLpushRecord(walWriter, key, element);
        walWriter.flush(); walChannel.force(false);
    }

    private void appendSadd(String key, String member) throws IOException {
        writeSaddRecord(walWriter, key, member);
        walWriter.flush(); walChannel.force(false);
    }

    /**
     * Writes a two-field length-prefix WAL record.
     * Format: {@code <op>|<key-len>|<key>|<val-len>|<val>\n}
     */
    private static void writeTwoFieldRecord(OutputStream out, char op,
                                            String key, String value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        // Build the header + key + separator + value length + separator as one string to minimise
        // write calls; then append the raw bytes for key and value.
        String header = op + "" + SEP + kb.length + SEP;
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(kb);
        String mid = "" + SEP + vb.length + SEP;
        out.write(mid.getBytes(StandardCharsets.UTF_8));
        out.write(vb);
        out.write(LF);
    }

    private static void writeSetRecord(OutputStream out, String key, String value)
            throws IOException {
        writeTwoFieldRecord(out, OP_SET, key, value);
    }

    private static void writeSetExRecord(OutputStream out, String key, String value,
                                         long expiryMs) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        String header = OP_SET_EX + "" + SEP + kb.length + SEP;
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(kb);
        String mid = "" + SEP + vb.length + SEP;
        out.write(mid.getBytes(StandardCharsets.UTF_8));
        out.write(vb);
        String tail = "" + SEP + expiryMs + LF;
        out.write(tail.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeDelRecord(OutputStream out, String key) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        String record = OP_DEL + "" + SEP + kb.length + SEP;
        out.write(record.getBytes(StandardCharsets.UTF_8));
        out.write(kb);
        out.write(LF);
    }

    private static void writeLpushRecord(OutputStream out, String key, String element)
            throws IOException {
        writeTwoFieldRecord(out, OP_LPUSH, key, element);
    }

    private static void writeSaddRecord(OutputStream out, String key, String member)
            throws IOException {
        writeTwoFieldRecord(out, OP_SADD, key, member);
    }

    // -------------------------------------------------------------------------
    // WAL Recovery
    // -------------------------------------------------------------------------

    /**
     * Replays the WAL from disk to reconstruct the in-memory index and expiry map.
     * Partial trailing records (due to a crash mid-write) are silently skipped.
     * Expired string keys found during recovery are not loaded.
     */
    @SuppressWarnings("unchecked")
    private void recover() throws IOException {
        if (!Files.exists(walPath)) {
            LOG.info("[StorageEngine] No WAL found — starting fresh.");
            return;
        }
        LOG.info("[StorageEngine] Replaying WAL: " + walPath);
        long linesProcessed = 0, linesCorrupted = 0, keysRecovered = 0;
        long now = System.currentTimeMillis();

        try (InputStream fis = Files.newInputStream(walPath);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(fis, StandardCharsets.UTF_8), READ_BUFFER_BYTES)) {

            String line;
            while ((line = reader.readLine()) != null) {
                linesProcessed++;
                try {
                    WalRecord rec = parseRecord(line);
                    if (rec == null) { linesCorrupted++; continue; }

                    switch (rec.op()) {
                        case OP_SET -> {
                            index.put(rec.key(), rec.value());
                            expiry.remove(rec.key());
                            keysRecovered++;
                        }
                        case OP_SET_EX -> {
                            long exp = rec.expiryMs();
                            if (now > exp) {
                                index.remove(rec.key()); expiry.remove(rec.key());
                            } else {
                                index.put(rec.key(), rec.value());
                                expiry.put(rec.key(), exp);
                                keysRecovered++;
                            }
                        }
                        case OP_DEL -> {
                            index.remove(rec.key()); expiry.remove(rec.key());
                            keysRecovered++;
                        }
                        case OP_LPUSH -> {
                            // Key may already hold a list from earlier records.
                            Object existing = index.get(rec.key());
                            CopyOnWriteArrayList<String> list =
                                (existing instanceof CopyOnWriteArrayList)
                                    ? (CopyOnWriteArrayList<String>) existing
                                    : new CopyOnWriteArrayList<>();
                            list.add(0, rec.value());   // prepend
                            index.put(rec.key(), list);
                            keysRecovered++;
                        }
                        case OP_SADD -> {
                            Object existing = index.get(rec.key());
                            Set<String> set = (existing instanceof Set)
                                ? (Set<String>) existing
                                : ConcurrentHashMap.newKeySet();
                            set.add(rec.value());
                            index.put(rec.key(), set);
                            keysRecovered++;
                        }
                        default -> linesCorrupted++;
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
     * Returns {@code null} if the line is malformed or the opcode is unrecognised.
     */
    private static WalRecord parseRecord(String line) {
        if (line == null || line.isEmpty()) return null;
        char op = line.charAt(0);

        if (op == OP_DEL) {
            // D|<keyLen>|<key>
            int idx1 = line.indexOf(SEP, 2);
            if (idx1 < 0) return null;
            int keyLen;
            try { keyLen = Integer.parseInt(line.substring(2, idx1)); }
            catch (NumberFormatException e) { return null; }
            int keyStart = idx1 + 1, keyEnd = keyStart + keyLen;
            if (keyEnd > line.length()) return null;
            return new WalRecord(OP_DEL, line.substring(keyStart, keyEnd), null, 0L);
        }

        // All other opcodes share the two-field key+value format.
        if (op == OP_SET || op == OP_SET_EX || op == OP_LPUSH || op == OP_SADD) {
            int idx1 = line.indexOf(SEP, 2);
            if (idx1 < 0) return null;
            int keyLen;
            try { keyLen = Integer.parseInt(line.substring(2, idx1)); }
            catch (NumberFormatException e) { return null; }
            int keyStart = idx1 + 1, keyEnd = keyStart + keyLen;
            if (keyEnd >= line.length() || line.charAt(keyEnd) != SEP) return null;
            String key = line.substring(keyStart, keyEnd);

            int idx2 = line.indexOf(SEP, keyEnd + 1);
            if (idx2 < 0) return null;
            int valLen;
            try { valLen = Integer.parseInt(line.substring(keyEnd + 1, idx2)); }
            catch (NumberFormatException e) { return null; }
            int valStart = idx2 + 1, valEnd = valStart + valLen;
            if (valEnd > line.length()) return null;
            String value = line.substring(valStart, valEnd);

            if (op == OP_SET || op == OP_LPUSH || op == OP_SADD) {
                return new WalRecord(op, key, value, 0L);
            }
            // OP_SET_EX: trailing |<expiryMs>
            if (valEnd >= line.length() || line.charAt(valEnd) != SEP) return null;
            long expiryMs;
            try { expiryMs = Long.parseLong(line.substring(valEnd + 1)); }
            catch (NumberFormatException e) { return null; }
            return new WalRecord(OP_SET_EX, key, value, expiryMs);
        }

        return null;  // unrecognised opcode
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void validateKey(String key) {
        if (key == null || key.isEmpty())
            throw new IllegalArgumentException("Key must be non-null and non-empty.");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return String.format("%.2f KiB", bytes / 1024.0);
        if (bytes < 1024L*1024*1024) return String.format("%.2f MiB", bytes / (1024.0*1024));
        return String.format("%.2f GiB", bytes / (1024.0*1024*1024));
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (visible to WebServer and tests in default package)
    // -------------------------------------------------------------------------

    /** Returns the number of live keys currently in the index. */
    int liveKeyCount() { return index.size(); }

    /**
     * Returns a read-only snapshot of the index for the key browser / tests.
     * Values are Object (String, List&lt;String&gt;, or Set&lt;String&gt;).
     */
    Map<String, Object> indexSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(index));
    }

    /** Returns a read-only snapshot of the expiry map (for tests / WebServer). */
    Map<String, Long> expirySnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(expiry));
    }

    // -------------------------------------------------------------------------
    // Internal record type
    // -------------------------------------------------------------------------

    /**
     * Parsed representation of a single WAL record.
     *
     * @param op       operation character: S, X, D, L, or A
     * @param key      the key
     * @param value    the value/element (null for DEL records)
     * @param expiryMs absolute expiry epoch millis (0 for non-TTL records)
     */
    private record WalRecord(char op, String key, String value, long expiryMs) {}
}
