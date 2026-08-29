# KV-Store

> **A crash-safe, WAL-backed embedded Key-Value Store in pure Java 25.**  
> Zero third-party dependencies · O(1) reads · Concurrent read/write · Atomic compaction

---

## Overview

KV-Store is a self-contained, embeddable key-value database implemented entirely with the Java 25 standard library.  It combines an in-memory `ConcurrentHashMap` index for O(1) reads with an append-only Write-Ahead Log (WAL) for durability, delivering a developer experience close to embedded stores like RocksDB or LevelDB — without a single Maven/Gradle dependency.

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                      Caller / CLI                       │
└───────────────────────────┬────────────────────────────┘
                            │
            ┌───────────────▼───────────────┐
            │         StorageEngine          │
            │                               │
            │  ┌─────────────────────────┐  │
            │  │  ReentrantReadWriteLock │  │
            │  └──────┬──────────────────┘  │
            │         │                      │
            │   ┌─────▼──────┐  ┌─────────┐ │
            │   │ READ path  │  │ WRITE   │ │
            │   │ (shared)   │  │ path    │ │
            │   └─────┬──────┘  └────┬────┘ │
            │         │              │       │
            │   ┌─────▼──────────────▼────┐ │
            │   │   ConcurrentHashMap     │ │
            │   │   (in-memory index)     │ │
            │   └─────────────┬───────────┘ │
            │                 │  (write only)│
            │   ┌─────────────▼───────────┐ │
            │   │   Append-Only WAL File  │ │
            │   │   (BufferedOutputStream │ │
            │   │    + FileChannel.force) │ │
            │   └─────────────────────────┘ │
            └───────────────────────────────┘

WAL Record Formats
──────────────────
  SET  →  S|<key-byte-len>|<key>|<val-byte-len>|<value>\n
  DEL  →  D|<key-byte-len>|<key>\n

Recovery Sequence (on startup)
───────────────────────────────
  1. Open WAL in read mode
  2. Parse each length-prefixed record
  3. Apply SET / DEL ops to the in-memory map
  4. Skip any partial trailing record (crash safety)
  5. Re-open WAL in append mode

Compaction Sequence
────────────────────
  1. Acquire exclusive write lock
  2. Snapshot in-memory index
  3. Write snapshot → kv.wal.tmp (temp file)
  4. fsync temp file
  5. Atomic rename: kv.wal.tmp → kv.wal
  6. Re-open WAL in append mode
  7. Release lock
```

---

## Build & Run (One Step)

### Prerequisites

- JDK 25+ installed (`java --version` should show 25+)

### Compile

```bash
# From the project root
mkdir -p out
javac -d out src/StorageEngine.java src/Main.java
```

### Run the interactive CLI

```bash
java -cp out Main
# Optional: specify a custom WAL path
java -cp out Main /path/to/mydb.wal
```

### Run the test suite

```bash
javac -cp out -d out tests/StorageEngineTest.java
java -cp out StorageEngineTest
# Exits with code 0 on all-pass, 1 on any failure (CI-friendly)
```

### One-liner (compile + run tests)

```bash
mkdir -p out && javac -d out src/StorageEngine.java src/Main.java tests/StorageEngineTest.java && java -cp out StorageEngineTest
```

---

## CLI Commands

| Command | Description |
|---|---|
| `SET <key> <value>` | Store a value. Values may contain spaces. |
| `GET <key>` | Retrieve a value. Prints `(nil)` if not found. |
| `DELETE <key>` | Remove a key. Writes a tombstone to the WAL. |
| `COMPACT` | Rewrite the WAL, eliminating stale entries. |
| `STATS` | Print live telemetry and WAL disk usage. |
| `HELP` | Display the command reference. |
| `EXIT` | Flush, close, and quit. |

---

## Concurrency Model

KV-Store uses a **single fair `ReentrantReadWriteLock`** to coordinate access:

- **Readers** (`GET`) acquire the *read lock* — multiple readers run in parallel without contention.
- **Writers** (`SET`, `DELETE`, `COMPACT`) acquire the *write lock* exclusively — they block until all readers finish, then execute alone.
- **Telemetry counters** (`opSets`, `opGets`, etc.) use `AtomicLong` and are updated outside the lock, so they never add contention to the hot path.
- **ConcurrentHashMap** provides additional segment-level concurrency that is preserved even though the RWLock serialises writes — this makes future lock-free reads possible with only minor refactoring.

```
Thread A (GET) ──► read lock  ──► ConcurrentHashMap.get ──► release
Thread B (GET) ──► read lock  ──► ConcurrentHashMap.get ──► release   [parallel]
Thread C (SET) ──► write lock ──► ConcurrentHashMap.put
                                ──► WAL append + fsync ──► release     [exclusive]
```

---

## Durability Guarantees

| Event | Behaviour |
|---|---|
| Normal shutdown | `BufferedOutputStream.flush()` + `FileChannel.force()` called from `close()` |
| Ungraceful exit (Ctrl-C) | JVM shutdown hook calls `engine.close()` — same guarantee |
| Power loss mid-write | Recovery parser skips any partial trailing record |
| Disk full during compaction | Rename is atomic; the original WAL is never removed until the temp file is complete |

> **Honest limit:** If the OS crashes after the app writes to the page cache but *before* `force()` returns, data in the buffer may be lost.  This matches the durability model of most embedded stores that do not use `O_DSYNC`.

---

## Technical Trade-offs

| Concern | Trade-off |
|---|---|
| **Read amplification** | Zero — reads are served entirely from the in-memory index. |
| **Write amplification** | Each SET writes one WAL record plus a small metadata header (type, lengths). No separate MemTable flush cycle. |
| **Space amplification** | Without compaction, the WAL grows unboundedly. Run `COMPACT` periodically or trigger it programmatically. |
| **Memory** | The entire key-space must fit in RAM. KV-Store is not suitable for datasets larger than available heap. |
| **Atomicity** | Each individual operation is atomic; multi-key transactions are not supported. |
| **Ordering** | The WAL is append-only; range scans require iterating `ConcurrentHashMap.entrySet()` which is unordered. |
| **Crash window** | The durability window is one operation — only the very last in-flight write is at risk on power loss. |

---

## Project Layout

```
kv-store/
├── src/
│   ├── StorageEngine.java    # Core engine: WAL, index, compaction, recovery
│   └── Main.java             # Interactive ANSI CLI
├── tests/
│   └── StorageEngineTest.java # Pure-JDK test suite (11 test categories)
├── .zero-dep.toml            # Hackathon manifest
├── README.md                 # This file
└── STDLIB.md                 # Standard-library substitution matrix
```

---

## License

MIT — see [LICENSE](LICENSE).
