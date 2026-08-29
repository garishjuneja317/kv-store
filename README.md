# KV-Store

> **A crash-safe, WAL-backed embedded Key-Value Store in pure Java 25.**  
> Zero third-party dependencies · O(1) reads · Concurrent read/write · Atomic compaction · Key expiration (TTL) · TCP server · HTTP dashboard

---

## Overview

KV-Store is a self-contained, embeddable key-value database implemented entirely with the Java 25 standard library. It combines an in-memory `ConcurrentHashMap` index for O(1) reads with an append-only Write-Ahead Log (WAL) for durability, delivering a developer experience close to embedded stores like RocksDB or LevelDB — without a single Maven/Gradle dependency.

**Phase 2 additions:**
- **Key Expiration (TTL)** — `SET key value EX <seconds>` with lazy eviction and an active background purger.
- **TCP Server** — `--server` flag launches a virtual-thread-per-connection TCP daemon on port 8080.
- **Reproducible Build** — `build.sh` / `build.bat` produce byte-for-byte identical `.class` files across runs.

**Phase 3 additions:**
- **HTTP Web UI** — `--web` flag launches a REST API server on port 8081 with an embedded SPA dashboard.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│              Caller / CLI  OR  TCP Client (via NetworkServer)      │
└───────────────────────────────┬────────────────────────────────────┘
                                │
              ┌─────────────────▼──────────────────┐
              │           StorageEngine            │
              │                                    │
              │  ┌──────────────────────────────┐  │
              │  │   ReentrantReadWriteLock     │  │
              │  └────┬─────────────────────────┘  │
              │       │                            │
              │  ┌────▼────────┐  ┌─────────────┐  │
              │  │  READ path  │  │  WRITE path │  │
              │  │  (shared)   │  │  (exclusive)│  │
              │  └────┬────────┘  └──────┬──────┘  │
              │       │                  │         │
              │  ┌────▼──────────────────▼───────┐ │
              │  │   ConcurrentHashMap (index)   │ │
              │  │   ConcurrentHashMap (expiry)  │ │
              │  └─────────────┬─────────────────┘ │
              │                │ (write only)      │
              │  ┌─────────────▼─────────────────┐ │
              │  │   Append-Only WAL File        │ │
              │  │   (BufferedOutputStream       │ │
              │  │    + FileChannel.force)       │ │
              │  └───────────────────────────────┘ │
              │                                    │
              │  ┌─────────────────────────────┐   │
              │  │  TTL Purger                 │   │
              │  │  (ScheduledExecutorService, │   │
              │  │   virtual thread, 1-s sweep)│   │
              │  └─────────────────────────────┘   │
              └────────────────────────────────────┘

WAL Record Formats
──────────────────
  SET (no TTL) → S|<key-byte-len>|<key>|<val-byte-len>|<value>\n
  SET (w/ TTL) → X|<key-byte-len>|<key>|<val-byte-len>|<value>|<expiry-epoch-ms>\n
  DEL          → D|<key-byte-len>|<key>\n

Network Architecture (--server mode)
──────────────────────────────────────
  TCP Client 1 ──► Virtual Thread 1 ─┐
  TCP Client 2 ──► Virtual Thread 2 ─┤──► StorageEngine (shared, thread-safe)
  TCP Client N ──► Virtual Thread N ─┘

Network Architecture (--web mode)
──────────────────────────────────
  Browser / curl ──► Virtual Thread ─► WebServer handler ─► StorageEngine
                     (one vthread per HTTP request via com.sun.net.httpserver)

Recovery Sequence (on startup)
───────────────────────────────
  1. Open WAL in read mode
  2. Parse each length-prefixed record (S / X / D)
  3. Apply SET / DEL ops; skip X records whose expiry has already passed
  4. Skip any partial trailing record (crash safety)
  5. Re-open WAL in append mode

Compaction Sequence
────────────────────
  1. Acquire exclusive write lock
  2. Purge all expired keys (write tombstones, update index)
  3. Snapshot in-memory index + expiry map
  4. Write snapshot → kv.wal.tmp (S or X records as appropriate)
  5. fsync temp file
  6. Atomic rename: kv.wal.tmp → kv.wal
  7. Re-open WAL in append mode
  8. Release lock
```

---

## Build & Run

### Prerequisites

- JDK 25+ (`java --version` should show 25+)

### Reproducible Build (recommended)

```bat
REM Windows
build.bat

REM Linux / macOS
chmod +x build.sh && ./build.sh
```

Flags:
| Flag | Description |
|---|---|
| `--clean` | Delete `out/` first, then compile |
| `--tests-only` | Compile only the test suite (sources already built) |

**Why reproducible?**  
The scripts use `-g:none` (strip debug metadata), `-implicit:none` (deterministic dependency graph), `-encoding UTF-8` (locale-independent), and sort source files alphabetically before passing them to `javac`. Running the script twice produces bit-identical `.class` files.

```bat
REM Verify reproducibility on Windows:
build.bat
xcopy /e /i /q out out1
build.bat --clean
fc /b out\StorageEngine.class out1\StorageEngine.class && echo REPRODUCIBLE
```

### Manual Compile

```bash
mkdir -p out
javac -d out src/StorageEngine.java src/NetworkServer.java src/WebServer.java src/Main.java
```

### Run the interactive CLI

```bash
java -cp out Main
# Optional: specify a custom WAL path
java -cp out Main /path/to/mydb.wal
```

### Run the TCP server

```bash
# Default port 8080
java -cp out Main --server

# Custom port
java -cp out Main --server --port 9090

# Custom port + custom WAL path
java -cp out Main --server --port 9090 /data/mydb.wal
```

Connect with any TCP client:

```bash
telnet localhost 8080
# or
nc localhost 8080
```

### Run the test suite

```bash
javac -cp out -d out tests/StorageEngineTest.java
java -cp out StorageEngineTest
# Exits with code 0 on all-pass, 1 on any failure (CI-friendly)
```

---

## HTTP Web UI  (`--web`)

> [!IMPORTANT]
> **Hackathon compliance — demo-grade API notice.**  
> `WebServer.java` uses `com.sun.net.httpserver.HttpServer` (module `jdk.httpserver`),
> a JDK-internal, **demo-grade** HTTP server that has shipped with every JDK since
> Java 6. It is **not** a third-party Maven/Gradle dependency and therefore satisfies
> the zero-dependency constraint of Track D. However, it is outside the official
> `java.*`/`javax.*` namespace, Oracle does not guarantee its stability across future
> JDK versions, and it is explicitly unsuitable for production workloads. Its use here
> is solely for hackathon demonstration purposes.

### Launch the web server

```bash
# HTTP dashboard on :8081 only
java -cp out Main --web

# Custom port
java -cp out Main --web --web-port 9000

# TCP server + HTTP dashboard simultaneously (shared StorageEngine)
java -cp out Main --server --web

# All options combined
java -cp out Main --server --port 8080 --web --web-port 8081 /data/mydb.wal
```

Open **http://localhost:8081** in any browser to access the dashboard.

### Dashboard features

- **Live stats panel** — auto-refreshes every 3 s (live keys, WAL size, SET/GET/TTL op counters, expired key count)
- **Operations panel** — tabbed interface for `SET` (with optional TTL), `GET`, `DELETE`, `TTL`, and `COMPACT`
- **JSON response console** — syntax-highlighted JSON for every API call, with one-click copy
- **Key browser** — alphabetically sorted list of all live keys with remaining TTL; click a key to auto-fill the GET form

### REST API reference

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/` | — | Serves the embedded SPA dashboard (HTML) |
| `GET` | `/api/get?key=X` | — | `{"ok":true,"key":"X","value":"V"}` or 404 error |
| `POST` | `/api/set` | Form body: `key=X&value=V[&ttl=N]` | `{"ok":true,"key":"X"[,"ttl":N]}` |
| `DELETE` | `/api/delete?key=X` | — | `{"ok":true,"key":"X"}` |
| `GET` | `/api/ttl?key=X` | — | `{"ok":true,"key":"X","ttl":N}` (N = -1 no TTL, -2 not found) |
| `GET` | `/api/stats` | — | Stats fields as a flat JSON object |
| `GET` | `/api/keys` | — | `{"ok":true,"keys":[{"key":"X","ttl":N},…]}` |
| `POST` | `/api/compact` | — | `{"ok":true,"elapsed_ms":N,"live_keys":N}` |

All JSON is constructed via raw string concatenation — no JSON library is used.
All responses include `Access-Control-Allow-Origin: *` for browser compatibility.

### Example `curl` session

```bash
# Store a key with a 60-second TTL
curl -X POST http://localhost:8081/api/set \
     -d 'key=session&value=tok-abc123&ttl=60'
# → {"ok":true,"key":"session","ttl":60}

# Retrieve it
curl 'http://localhost:8081/api/get?key=session'
# → {"ok":true,"key":"session","value":"tok-abc123"}

# Check TTL
curl 'http://localhost:8081/api/ttl?key=session'
# → {"ok":true,"key":"session","ttl":57}

# Compact
curl -X POST http://localhost:8081/api/compact
# → {"ok":true,"elapsed_ms":3,"live_keys":1}

# Stats
curl http://localhost:8081/api/stats
# → {"Live keys":"1","WAL file size":"30 B", ...}
```

---

## CLI Commands

| Command | Description |
|---|---|
| `SET <key> <value>` | Store a value. Values may contain spaces. |
| `SET <key> <value> EX <seconds>` | Store a value with a time-to-live. |
| `GET <key>` | Retrieve a value. Prints `(nil)` if not found or expired. |
| `DELETE <key>` | Remove a key. Writes a tombstone to the WAL. |
| `TTL <key>` | Show remaining TTL in seconds (`-1` = no TTL, `-2` = not found). |
| `COMPACT` | Rewrite the WAL, eliminating stale and expired entries. |
| `STATS` | Print live telemetry and WAL disk usage. |
| `HELP` | Display the command reference. |
| `EXIT` | Flush, close, and quit. |

### TTL Examples

```
kv> SET session tok-abc123 EX 3600
OK  session → tok-abc123  (TTL=3600s)

kv> TTL session
3598s  — remaining TTL for session

kv> GET session
"tok-abc123"

# (after TTL elapses)
kv> GET session
(nil)  — key not found: session

kv> TTL session
-2  — key not found or expired
```

---

## TCP Server Protocol

The server uses a plain-text, newline-delimited protocol identical to the CLI.

### Supported Commands (over TCP)

| Request | Response |
|---|---|
| `SET key value [EX n]` | `OK` or `OK (TTL=Ns)` |
| `GET key` | `"value"` or `(nil)` |
| `DELETE key` | `DELETED key` |
| `TTL key` | integer or `-1`/`-2` with description |
| `COMPACT` | `COMPACT OK (N ms)` |
| `STATS` | multi-line stats block |
| `HELP` | command reference |
| `QUIT` | `BYE` (server closes connection) |

### Example Session

```
$ nc localhost 8080
KV-STORE ready. Type HELP for commands.
SET username Garish EX 300
OK (TTL=300s)
GET username
"Garish"
TTL username
299
COMPACT
COMPACT OK (5 ms)
QUIT
BYE
```

### Concurrency Model (Server)

Each TCP connection is handled in its own **Java virtual thread**. There is no fixed thread pool limit — the server can sustain thousands of simultaneous connections. All commands route through the same `StorageEngine` instance, which is internally guarded by a `ReentrantReadWriteLock`.

```
TCP Client 1 ──► VThread-1 ──► StorageEngine.get()  [read lock — parallel]
TCP Client 2 ──► VThread-2 ──► StorageEngine.get()  [read lock — parallel]
TCP Client 3 ──► VThread-3 ──► StorageEngine.set()  [write lock — exclusive]
```

---

## Key Expiration (TTL)

### How It Works

KV-Store uses a **hybrid expiration strategy**:

| Strategy | Mechanism | Latency |
|---|---|---|
| **Lazy** | `GET` checks the expiry map before returning; returns `empty()` and evicts on first access after expiry | Zero cost when key is not expired |
| **Active** | Background `ScheduledExecutorService` (virtual thread) sweeps the expiry map every second | Bounded 1-second delay for cleanup |

### WAL Format Extension

Expired keys are persisted in the WAL with a new record type `X`:

```
X|<key-byte-len>|<key>|<val-byte-len>|<value>|<expiry-epoch-ms>
```

This is **backward-compatible**: old WAL files with only `S` and `D` records continue to replay correctly. During recovery, `X` records whose expiry timestamp has already passed are silently skipped (not loaded into the index).

### TTL and Compaction

`COMPACT` first runs a synchronous purge of all expired keys (writing tombstones to the WAL), then snapshots the live index. Keys with remaining TTL are written using `X` records, preserving their expiry through the compaction.

---

## Concurrency Model

KV-Store uses a **single fair `ReentrantReadWriteLock`** to coordinate access:

- **Readers** (`GET`) acquire the *read lock* — multiple readers run in parallel without contention.
- **Writers** (`SET`, `DELETE`, `COMPACT`, TTL purge) acquire the *write lock* exclusively — they block until all readers finish, then execute alone.
- **Telemetry counters** (`opSets`, `opGets`, etc.) use `AtomicLong` and are updated outside the lock, so they never add contention to the hot path.
- **ConcurrentHashMap** provides additional segment-level concurrency for the index and expiry maps.

```
Thread A (GET) ──► read lock  ──► index.get + expiry check ──► release
Thread B (GET) ──► read lock  ──► index.get + expiry check ──► release   [parallel]
Thread C (SET) ──► write lock ──► index.put → WAL append + fsync ──► release [exclusive]
TTL Purger     ──► write lock ──► sweep expiry → tombstones ──► release   [1-s period]
```

---

## Durability Guarantees

| Event | Behaviour |
|---|---|
| Normal shutdown | `BufferedOutputStream.flush()` + `FileChannel.force()` called from `close()` |
| Ungraceful exit (Ctrl-C) | JVM shutdown hook calls `engine.close()` — same guarantee |
| Power loss mid-write | Recovery parser skips any partial trailing record |
| Disk full during compaction | Rename is atomic; the original WAL is never removed until the temp file is complete |
| TTL expiry during crash | On restart, `X` records with elapsed expiry are skipped — no stale data loaded |

> **Honest limit:** If the OS crashes after the app writes to the page cache but *before* `force()` returns, data in the buffer may be lost. This matches the durability model of most embedded stores that do not use `O_DSYNC`.

---

## Technical Trade-offs

| Concern | Trade-off |
|---|---|
| **Read amplification** | Zero — reads are served entirely from the in-memory index. |
| **Write amplification** | Each SET writes one WAL record plus a small metadata header. No separate MemTable flush cycle. |
| **Space amplification** | Without compaction, the WAL grows unboundedly. Run `COMPACT` periodically. |
| **Memory** | The entire key-space (plus expiry map) must fit in RAM. Not suitable for datasets larger than available heap. |
| **Atomicity** | Each individual operation is atomic; multi-key transactions are not supported. |
| **TTL precision** | Active purger has ±1 s granularity; lazy eviction is exact on first access. |
| **TCP security** | The TCP server performs no authentication. Bind to loopback in production. |
| **HTTP server** | Uses `com.sun.net.httpserver` (demo-grade, JDK-internal). Not suitable for production HTTP traffic. |

---

## Project Layout

```
kv-store/
├── src/
│   ├── StorageEngine.java    # Core engine: WAL, index, compaction, recovery, TTL
│   ├── NetworkServer.java    # TCP server: virtual threads, command router (port 8080)
│   ├── WebServer.java        # HTTP server + embedded SPA dashboard (port 8081, demo-grade)
│   └── Main.java             # Entry point: CLI + --server + --web modes
├── tests/
│   └── StorageEngineTest.java # Pure-JDK test suite (16 test categories, incl. TTL)
├── build.sh                  # Reproducible build (Linux/macOS)
├── build.bat                 # Reproducible build (Windows)
├── .zero-dep.toml            # Hackathon manifest
├── README.md                 # This file
└── STDLIB.md                 # Standard-library substitution matrix
```

---

## License

MIT — see [LICENSE](LICENSE).
