import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Scanner;

/**
 * Main — entry point for the embedded key-value store.
 *
 * <h2>Modes of Operation</h2>
 * <pre>
 *   java Main                                  Interactive CLI (default)
 *   java Main /path/to/db.wal                  CLI with custom WAL path
 *   java Main --server                         TCP server on port 8080
 *   java Main --server --port 9090             TCP server on port 9090
 *   java Main --web                            HTTP dashboard on port 8081
 *   java Main --server --web                   Both servers, shared engine
 *   java Main --server --requirepass secret    TCP server with auth
 *   java Main --web   --requirepass secret     HTTP server with Bearer auth
 * </pre>
 *
 * <h2>Supported CLI Commands</h2>
 * <pre>
 *   SET      &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]  Store a value with optional TTL
 *   GET      &lt;key&gt;                            Retrieve a string value
 *   DELETE   &lt;key&gt;                            Remove a key (any type)
 *   TTL      &lt;key&gt;                            Show remaining seconds (-1=no TTL)
 *   TYPE     &lt;key&gt;                            Show value type (string/list/set/none)
 *   LPUSH    &lt;key&gt; &lt;element&gt;                  Prepend element to a list
 *   LRANGE   &lt;key&gt; &lt;start&gt; &lt;end&gt;             Get list slice (0-indexed, -1=last)
 *   SADD     &lt;key&gt; &lt;member&gt;                   Add member to a set
 *   SMEMBERS &lt;key&gt;                            Get all set members
 *   COMPACT                                  Rewrite the WAL, eliminating stale entries
 *   STATS                                    Print live telemetry and disk usage
 *   HELP                                     Display this help text
 *   EXIT                                     Flush, close the engine, and quit
 * </pre>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>All ANSI colour codes are stripped when stdout is not a terminal (detected
 *       via the {@code NO_COLOR} environment variable or absence of a system console).</li>
 *   <li>The engine is registered as a JVM shutdown hook so that an ungraceful
 *       {@code Ctrl-C} still flushes the WAL buffer.</li>
 *   <li>In server mode the main thread blocks indefinitely; send SIGINT / Ctrl-C to
 *       trigger the shutdown hook and close cleanly.</li>
 * </ul>
 */
public class Main {

    // -------------------------------------------------------------------------
    // ANSI colour palette
    // -------------------------------------------------------------------------

    private static final boolean COLOUR_ENABLED = detectColour();

    private static final String RESET   = ansi("\u001B[0m");
    private static final String BOLD    = ansi("\u001B[1m");
    private static final String CYAN    = ansi("\u001B[96m");
    private static final String GREEN   = ansi("\u001B[92m");
    private static final String YELLOW  = ansi("\u001B[93m");
    private static final String RED     = ansi("\u001B[91m");
    private static final String GREY    = ansi("\u001B[90m");
    private static final String MAGENTA = ansi("\u001B[95m");
    private static final String BLUE    = ansi("\u001B[94m");

    // -------------------------------------------------------------------------
    // Banner / help text
    // -------------------------------------------------------------------------

    private static final String BANNER =
        BOLD + CYAN +
        " ██╗  ██╗██╗   ██╗      ███████╗████████╗ ██████╗ ██████╗ ███████╗\n" +
        " ██║ ██╔╝██║   ██║      ██╔════╝╚══██╔══╝██╔═══██╗██╔══██╗██╔════╝\n" +
        " █████╔╝ ██║   ██║      ███████╗   ██║   ██║   ██║██████╔╝█████╗  \n" +
        " ██╔═██╗ ╚██╗ ██╔╝      ╚════██║   ██║   ██║   ██║██╔══██╗██╔══╝  \n" +
        " ██║  ██╗ ╚████╔╝       ███████║   ██║   ╚██████╔╝██║  ██║███████╗\n" +
        " ╚═╝  ╚═╝  ╚═══╝        ╚══════╝   ╚═╝    ╚═════╝ ╚═╝  ╚═╝╚══════╝\n" +
        RESET + GREY +
        "  Zero-Dependency Embedded KV Store — Track D (Data & Storage)\n" +
        "  Type HELP for available commands.\n" +
        RESET;

    private static final String HELP_TEXT =
        BOLD + "\nAvailable Commands\n" + RESET +
        "  " + CYAN + "SET      <key> <value> [EX <seconds>]" + RESET + "  — store a string with optional TTL\n" +
        "  " + CYAN + "GET      <key>"         + RESET + "                         — retrieve a string value\n" +
        "  " + CYAN + "DELETE   <key>"         + RESET + "                         — remove a key (any type)\n" +
        "  " + CYAN + "TTL      <key>"         + RESET + "                         — remaining TTL (-1=no TTL, -2=missing)\n" +
        "  " + CYAN + "TYPE     <key>"         + RESET + "                         — returns: string | list | set | none\n" +
        "  " + CYAN + "LPUSH    <key> <element>" + RESET + "                — prepend element to a list\n" +
        "  " + CYAN + "LRANGE   <key> <start> <end>" + RESET + "            — get list slice (0-indexed, -1=last)\n" +
        "  " + CYAN + "SADD     <key> <member>" + RESET + "                  — add member to a set\n" +
        "  " + CYAN + "SMEMBERS <key>"         + RESET + "                         — get all set members\n" +
        "  " + CYAN + "COMPACT" + RESET + "                                       — rewrite WAL, eliminate stale entries\n" +
        "  " + CYAN + "STATS"   + RESET + "                                         — show telemetry and disk usage\n" +
        "  " + CYAN + "HELP"    + RESET + "                                          — display this message\n" +
        "  " + CYAN + "EXIT"    + RESET + "                                          — save and quit\n";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        // ── Argument parsing ──────────────────────────────────────────────────
        boolean serverMode = false;
        boolean webMode    = false;
        int     port       = 8080;
        int     webPort    = 8081;
        String  walArg     = "kv.wal";
        String  password   = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server"      -> serverMode = true;
                case "--web"         -> webMode    = true;
                case "--port"        -> {
                    if (i + 1 >= args.length) {
                        System.err.println(RED + "[ERROR] --port requires an integer argument." + RESET);
                        System.exit(1);
                    }
                    try { port = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) {
                        System.err.println(RED + "[ERROR] Invalid port: " + args[i] + RESET);
                        System.exit(1);
                    }
                }
                case "--web-port"    -> {
                    if (i + 1 >= args.length) {
                        System.err.println(RED + "[ERROR] --web-port requires an integer argument." + RESET);
                        System.exit(1);
                    }
                    try { webPort = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) {
                        System.err.println(RED + "[ERROR] Invalid web-port: " + args[i] + RESET);
                        System.exit(1);
                    }
                }
                case "--requirepass" -> {
                    if (i + 1 >= args.length) {
                        System.err.println(RED + "[ERROR] --requirepass requires a password argument." + RESET);
                        System.exit(1);
                    }
                    password = args[++i];
                }
                default -> {
                    if (!args[i].startsWith("--")) walArg = args[i];
                }
            }
        }

        System.out.println(BANNER);

        // ── Open storage engine ───────────────────────────────────────────────
        StorageEngine engine;
        try {
            engine = new StorageEngine(Paths.get(walArg));
        } catch (IOException e) {
            System.err.println(RED + "[ERROR] Failed to open storage engine: " + e.getMessage() + RESET);
            System.exit(1);
            return;
        }

        // Register shutdown hook for ungraceful exits (Ctrl-C, kill signal).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { engine.close(); } catch (IOException ignored) {}
        }, "kv-shutdown-hook"));

        // ── Dispatch to server / web / CLI ───────────────────────────────────
        if (!serverMode && !webMode) {
            runCli(engine);
            return;
        }
        if (serverMode) startTcpServer(engine, port, password);
        if (webMode)    startWebServer(engine, webPort, password);

        // Block the main thread. The JVM shutdown hook closes the engine.
        System.out.println(GREY + "  Press Ctrl-C to stop." + RESET + "\n");
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // -------------------------------------------------------------------------
    // Server mode helpers
    // -------------------------------------------------------------------------

    /** Starts the TCP server (non-blocking). Prints the connection banner. */
    private static void startTcpServer(StorageEngine engine, int port, String password) {
        NetworkServer server = new NetworkServer(engine, port, password);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println(RED + "[ERROR] Failed to bind TCP server on port " + port +
                ": " + e.getMessage() + RESET);
            System.exit(1);
            return;
        }
        System.out.println(BLUE + BOLD + "  ► TCP server listening on port " + port + RESET);
        System.out.println(GREY  + "  Connect with: telnet localhost " + port + RESET);
        if (password != null) System.out.println(YELLOW + "  Auth required: AUTH <password>" + RESET);
    }

    /** Starts the HTTP web server (non-blocking). Prints the URL banner. */
    private static void startWebServer(StorageEngine engine, int port, String password) {
        WebServer web = new WebServer(engine, port, password);
        try {
            web.start();
        } catch (IOException e) {
            System.err.println(RED + "[ERROR] Failed to bind HTTP server on port " + port +
                ": " + e.getMessage() + RESET);
            System.exit(1);
            return;
        }
        System.out.println(MAGENTA + BOLD + "  ► Web dashboard on http://localhost:" + port + RESET);
        System.out.println(GREY + "  API base: http://localhost:" + port + "/api/" + RESET);
        if (password != null) System.out.println(YELLOW + "  Auth required: Authorization: Bearer <password>" + RESET);
    }

    // -------------------------------------------------------------------------
    // Interactive CLI mode
    // -------------------------------------------------------------------------

    private static void runCli(StorageEngine engine) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print(BOLD + CYAN + "kv> " + RESET);
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String rawLine = scanner.nextLine().trim();
                if (rawLine.isEmpty()) continue;

                String[] parts   = rawLine.split("\\s+", 2);
                String   command = parts[0].toUpperCase();
                String   rest    = (parts.length > 1) ? parts[1] : "";

                try {
                    switch (command) {
                        case "SET"               -> handleSet(engine, rest);
                        case "GET"               -> handleGet(engine, rest);
                        case "DELETE", "DEL"     -> handleDelete(engine, rest);
                        case "TTL"               -> handleTtl(engine, rest);
                        case "TYPE"              -> handleType(engine, rest);
                        case "LPUSH"             -> handleLpush(engine, rest);
                        case "LRANGE"            -> handleLrange(engine, rest);
                        case "SADD"              -> handleSadd(engine, rest);
                        case "SMEMBERS"          -> handleSmembers(engine, rest);
                        case "COMPACT"           -> handleCompact(engine);
                        case "STATS"             -> handleStats(engine);
                        case "HELP"              -> System.out.println(HELP_TEXT);
                        case "EXIT", "QUIT"      -> {
                            System.out.println(GREY + "Closing engine and flushing WAL…" + RESET);
                            engine.close();
                            System.out.println(GREEN + "Goodbye." + RESET);
                            return;
                        }
                        default -> System.out.println(
                            YELLOW + "[WARN] Unknown command: " + BOLD + command +
                            RESET + YELLOW + ". Type HELP for usage." + RESET);
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    System.out.println(RED + "[ERROR] " + e.getMessage() + RESET);
                } catch (IOException e) {
                    System.out.println(RED + "[ERROR] I/O failure: " + e.getMessage() + RESET);
                }
            }
        } finally {
            try { engine.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // CLI command handlers
    // -------------------------------------------------------------------------

    /**
     * Handles: SET &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]
     *
     * <p>The value is everything after the first whitespace token following SET,
     * optionally followed by the {@code EX <seconds>} clause.
     * Examples:
     * <pre>
     *   SET username Garish Juneja
     *   SET session abc123 EX 3600
     * </pre>
     */
    private static void handleSet(StorageEngine engine, String rest) throws IOException {
        if (rest.isBlank()) {
            throw new IllegalArgumentException("Usage: SET <key> <value> [EX <seconds>]");
        }
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2) {
            throw new IllegalArgumentException(
                "Usage: SET <key> <value> [EX <seconds>]  — both key and value are required.");
        }
        String key      = kv[0];
        String valueRaw = kv[1];

        // Detect trailing "EX <n>" (case-insensitive, last two tokens).
        String[] tokens = valueRaw.split("\\s+");
        long     ttl    = 0;
        String   value;

        if (tokens.length >= 3 && "EX".equalsIgnoreCase(tokens[tokens.length - 2])) {
            try {
                ttl = Long.parseLong(tokens[tokens.length - 1]);
                if (ttl <= 0) throw new NumberFormatException("non-positive");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "EX value must be a positive integer, got: " + tokens[tokens.length - 1]);
            }
            int cutAt = valueRaw.lastIndexOf(tokens[tokens.length - 2]);
            value = valueRaw.substring(0, cutAt).stripTrailing();
        } else {
            value = valueRaw;
        }

        if (value.isEmpty()) {
            throw new IllegalArgumentException("Value must not be empty after stripping EX clause.");
        }

        if (ttl > 0) {
            engine.set(key, value, ttl);
            System.out.println(GREEN + "OK" + RESET + "  " +
                GREY + key + " → " + truncate(value, 80) + RESET +
                YELLOW + "  (TTL=" + ttl + "s)" + RESET);
        } else {
            engine.set(key, value);
            System.out.println(GREEN + "OK" + RESET + "  " +
                GREY + key + " → " + truncate(value, 80) + RESET);
        }
    }

    /**
     * Handles: GET &lt;key&gt;
     */
    private static void handleGet(StorageEngine engine, String rest) {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: GET <key>");
        }
        Optional<String> result = engine.get(key);
        if (result.isPresent()) {
            System.out.println(GREEN + "\"" + result.get() + "\"" + RESET);
        } else {
            System.out.println(YELLOW + "(nil)" + RESET +
                GREY + "  — key not found: " + key + RESET);
        }
    }

    /**
     * Handles: DELETE &lt;key&gt;
     */
    private static void handleDelete(StorageEngine engine, String rest) throws IOException {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: DELETE <key>");
        }
        engine.delete(key);
        System.out.println(GREEN + "DELETED" + RESET + "  " + GREY + key + RESET);
    }

    /**
     * Handles: TTL &lt;key&gt;
     * Prints the remaining time-to-live in seconds, or -1 if no TTL is set,
     * or -2 if the key does not exist or has expired.
     */
    private static void handleTtl(StorageEngine engine, String rest) {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: TTL <key>");
        }
        long remaining = engine.ttl(key);
        if (remaining == -1L) {
            System.out.println(CYAN + "-1" + RESET + GREY + "  — key exists, no TTL set" + RESET);
        } else if (remaining == -2L) {
            System.out.println(YELLOW + "-2" + RESET + GREY + "  — key not found or expired" + RESET);
        } else {
            System.out.println(GREEN + remaining + "s" + RESET +
                GREY + "  — remaining TTL for " + key + RESET);
        }
    }

    /** Handles: TYPE &lt;key&gt; */
    private static void handleType(StorageEngine engine, String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: TYPE <key>");
        String t = engine.type(key);
        System.out.println(CYAN + t + RESET + GREY + "  — type of key: " + key + RESET);
    }

    /** Handles: LPUSH &lt;key&gt; &lt;element&gt; */
    private static void handleLpush(StorageEngine engine, String rest) throws IOException {
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2 || kv[1].isBlank())
            throw new IllegalArgumentException("Usage: LPUSH <key> <element>");
        int len = engine.lpush(kv[0], kv[1]);
        System.out.println(GREEN + "(integer) " + len + RESET +
            GREY + "  — list length after push" + RESET);
    }

    /** Handles: LRANGE &lt;key&gt; &lt;start&gt; &lt;end&gt; */
    private static void handleLrange(StorageEngine engine, String rest) {
        String[] parts = rest.split("\\s+", 3);
        if (parts.length < 3) throw new IllegalArgumentException("Usage: LRANGE <key> <start> <end>");
        int start, end;
        try { start = Integer.parseInt(parts[1]); end = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("LRANGE start/end must be integers."); }
        java.util.List<String> elems = engine.lrange(parts[0], start, end);
        if (elems.isEmpty()) { System.out.println(GREY + "(empty list)" + RESET); return; }
        for (int i = 0; i < elems.size(); i++) {
            System.out.println(GREY + (i + 1) + ") " + RESET +
                GREEN + "\"" + elems.get(i) + "\"" + RESET);
        }
    }

    /** Handles: SADD &lt;key&gt; &lt;member&gt; */
    private static void handleSadd(StorageEngine engine, String rest) throws IOException {
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2 || kv[1].isBlank())
            throw new IllegalArgumentException("Usage: SADD <key> <member>");
        boolean added = engine.sadd(kv[0], kv[1]);
        System.out.println(GREEN + "(integer) " + (added ? 1 : 0) + RESET +
            GREY + "  — " + (added ? "member added" : "already a member") + RESET);
    }

    /** Handles: SMEMBERS &lt;key&gt; */
    private static void handleSmembers(StorageEngine engine, String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: SMEMBERS <key>");
        java.util.Set<String> members = engine.smembers(key);
        if (members.isEmpty()) { System.out.println(GREY + "(empty set)" + RESET); return; }
        int i = 1;
        for (String m : new java.util.TreeSet<>(members)) {
            System.out.println(GREY + (i++) + ") " + RESET +
                GREEN + "\"" + m + "\"" + RESET);
        }
    }

    /** Handles: COMPACT */
    private static void handleCompact(StorageEngine engine) throws IOException {
        System.out.println(GREY + "Running compaction…" + RESET);
        long before = System.currentTimeMillis();
        engine.compact();
        long elapsed = System.currentTimeMillis() - before;
        System.out.println(GREEN + "COMPACT OK" + RESET +
            GREY + "  (" + elapsed + " ms)" + RESET);
    }

    /**
     * Handles: STATS
     */
    private static void handleStats(StorageEngine engine) {
        System.out.println();
        System.out.println(MAGENTA + engine.stats() + RESET);
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /** Truncates a string to {@code maxLen} characters for display. */
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }

    /**
     * Returns the ANSI escape code only when colour output is enabled.
     * This is called at class-load time via static field initialisers.
     */
    private static String ansi(String code) {
        return COLOUR_ENABLED ? code : "";
    }

    /**
     * Detects whether ANSI colour output should be enabled.
     * Disabled when the {@code NO_COLOR} env var is set (https://no-color.org/)
     * or when stdout is not connected to a terminal.
     */
    private static boolean detectColour() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        // System.console() returns null when stdout is redirected / not a tty.
        return System.console() != null;
    }
}
