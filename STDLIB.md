# STDLIB.md — Standard-Library Substitution Matrix

> Complete matrix of **standard-library-for-package** substitutions used in  
> KV-Store, with rationale for each choice.

---

## Substitution Matrix

| # | External Package Replaced | JDK Standard-Library Replacement | Rationale |
|---|---|---|---|
| 1 | **Redis / Memcached** (remote KV cache) | `java.util.concurrent.ConcurrentHashMap` | Provides O(1) average-case get/put with segment-level concurrency, no network round-trip, no serialisation overhead. Sufficient for in-process key-value storage without a daemon process. |
| 2 | **RocksDB / LevelDB WAL** (write-ahead log) | `java.io.FileOutputStream` (append mode) + `java.io.BufferedOutputStream` + `java.nio.channels.FileChannel.force()` | Replicates the essential WAL pattern: buffered sequential writes + explicit fdatasync. The length-prefixed record format mirrors RocksDB's `WriteBatch` without any C++ native library. |
| 3 | **JUnit 5 / TestNG** (test framework) | Custom assertion harness (`assertTrue`, `assertEquals`, `assertNotEquals`) built with `java.util.concurrent.atomic.AtomicInteger` pass/fail counters | Zero-dependency, CI-friendly (non-zero exit on failure). Covers the 95% use case of assertion-based testing with readable `[PASS]`/`[FAIL]` output. |
| 4 | **Picocli / JCommander** (CLI argument parsing) | `java.util.Scanner` + `String.split()` + `switch` expression | `Scanner.nextLine()` delivers a full REPL loop. Manual `split("\\s+", 2)` extracts command + args, handling values with embedded spaces — all without reflection or annotation processing. |
| 5 | **Logback / Log4j 2** (structured logging) | `java.util.logging.Logger` (JUL) + `String.format()` for human-readable messages | JUL ships with every JDK, supports levels (`INFO`, `WARNING`, `SEVERE`), and can be redirected to files via `FileHandler`. `String.format()` produces structured log lines without a pattern-syntax DSL. |
| 6 | **Guava `Optional` / Vavr `Option`** | `java.util.Optional<T>` (since Java 8) | Provides the same absent-value semantics as Guava's `Optional` — `isPresent()`, `get()`, `orElse()`, `map()` — without a 3 MB transitive dependency. |
| 7 | **Protobuf / Avro / Kryo** (binary serialisation) | Custom length-prefixed text protocol over `java.io.OutputStream` + `java.nio.charset.StandardCharsets.UTF_8` | The format `S\|keyLen\|key\|valLen\|value\n` encodes arbitrary binary-safe payloads without a schema compiler. Length prefixes handle any embedded delimiters. `StandardCharsets.UTF_8` guarantees deterministic byte encoding. |
| 8 | **Google Guava `Striped<Lock>` / `ConcurrentUtils`** | `java.util.concurrent.locks.ReentrantReadWriteLock` (fair mode) | `ReentrantReadWriteLock` with `fair=true` prevents writer starvation and provides the same read-parallel / write-exclusive semantic as Guava's higher-level utilities, with zero overhead on the read hot-path. |
| 9 | **Atomic operations library (e.g., Eclipse Collections)** | `java.util.concurrent.atomic.AtomicLong` | Lock-free increment for telemetry counters (`opSets`, `opGets`, etc.). `AtomicLong.incrementAndGet()` uses a single CAS instruction — equivalent to what any external "atomic integer" library provides. |
| 10 | **Apache Commons IO `FileUtils.moveFile()`** | `java.nio.file.Files.move()` with `StandardCopyOption.ATOMIC_MOVE` | `Files.move(..., ATOMIC_MOVE)` delegates to `rename(2)` on Linux and `MoveFileExW` on Windows, providing the same crash-safe atomic swap that Apache Commons IO wraps internally — without the 200 KB dependency. |
| 11 | **Caffeine / Ehcache** (eviction / expiry cache) | `java.util.concurrent.ConcurrentHashMap` + manual `entrySet()` iteration | For this use case the full key-set fits in RAM and no TTL eviction is required. `ConcurrentHashMap` avoids the complexity of a separate eviction thread. If eviction were needed, `LinkedHashMap` in access-order mode + a `ReadWriteLock` replicates an LRU cache. |
| 12 | **Mockito / EasyMock** (test mocking) | Direct `StorageEngine` instantiation over a temp `java.nio.file.Files.createTempDirectory()` path | Integration tests against real file I/O are more valuable for a storage engine than mock-based unit tests. `createTempDirectory()` + `deleteIfExists()` provide hermetic, isolated test fixtures without a mocking framework. |
| 13 | **JMH** (microbenchmark harness) | `System.currentTimeMillis()` / `System.nanoTime()` inline timing | Sufficient for coarse throughput measurements and regression detection inside the test suite. Full JMH would add a 2 MB dependency and require a benchmark JAR. |
| 14 | **ANSI libraries (Jansi, Lanterna)** | Raw ANSI escape codes via `String` constants + `System.console() != null` detection | Jansi and Lanterna handle terminal detection and Windows console API, but for a hackathon CLI the 10 constants needed (`RESET`, `BOLD`, `RED`, etc.) are cheaper as string literals. The `NO_COLOR` env-var standard (https://no-color.org/) is honoured manually. |

---

## Design Principles

1. **Prefer composition over dependencies** — every external library adds a transitive graph that must be audited, updated, and loaded at startup.  Standard-library types have zero cold-start cost.
2. **Match the abstraction level** — we use `java.util.concurrent` primitives that map 1-to-1 to what higher-level libraries use internally, so there is no semantic gap.
3. **Explicit over implicit** — the WAL format is a plain text file readable with `cat`; the lock model is a single, named `ReentrantReadWriteLock`; the test harness is 50 lines of assertions.  No magic frameworks obscure behaviour.
4. **Durability parity** — `FileChannel.force(false)` is the same kernel call (`fdatasync`) that RocksDB issues after a WAL batch; we replicate the durability guarantee, not just the API shape.
