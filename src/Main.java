import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Scanner;

/**
 * Main — entry point for the embedded key-value store.
 *
 * <h2>Modes of Operation</h2>
 * <pre>
 *   java Main                        Interactive CLI (default)
 *   java Main /path/to/db.wal        CLI with custom WAL path
 *   java Main --server               TCP server on port 8080
 *   java Main --server --port 9090   TCP server on port 9090
 *   java Main --server /path/db.wal  TCP server with custom WAL path
 * </pre>
 *
 * <h2>Supported CLI Commands</h2>
 * <pre>
 *   SET    &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]  Store a value with optional TTL
 *   GET    &lt;key&gt;                            Retrieve a value
 *   DELETE &lt;key&gt;                            Remove a key (tombstone written to WAL)
 *   TTL    &lt;key&gt;                            Show remaining seconds for a key (-1 = no TTL)
 *   COMPACT                                 Rewrite the WAL, eliminating stale entries
 *   STATS                                   Print live telemetry and disk usage
 *   HELP                                    Display this help text
 *   EXIT                                    Flush, close the engine, and quit
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
        "  " + CYAN + "SET    <key> <value> [EX <seconds>]" + RESET +
            "  — store a key-value pair with optional TTL\n" +
        "  " + CYAN + "GET    <key>"          + RESET + "                        — retrieve the value for a key\n" +
        "  " + CYAN + "DELETE <key>"          + RESET + "                        — remove a key (writes tombstone to WAL)\n" +
        "  " + CYAN + "TTL    <key>"          + RESET + "                        — show remaining TTL in seconds (-1=no TTL, -2=not found)\n" +
        "  " + CYAN + "COMPACT"               + RESET + "                             — rewrite WAL, eliminating stale/expired entries\n" +
        "  " + CYAN + "STATS"                 + RESET + "                               — show telemetry and disk usage\n" +
        "  " + CYAN + "HELP"                  + RESET + "                                — display this message\n" +
        "  " + CYAN + "EXIT"                  + RESET + "                                — save and quit\n";

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

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server"   -> serverMode = true;
                case "--web"      -> webMode    = true;
                case "--port"     -> {
                    if (i + 1 >= args.length) {
                        System.err.println(RED + "[ERROR] --port requires an integer argument." + RESET);
                        System.exit(1);
                    }
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println(RED + "[ERROR] Invalid port: " + args[i] + RESET);
                        System.exit(1);
                    }
                }
                case "--web-port" -> {
                    if (i + 1 >= args.length) {
                        System.err.println(RED + "[ERROR] --web-port requires an integer argument." + RESET);
                        System.exit(1);
                    }
                    try {
                        webPort = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println(RED + "[ERROR] Invalid web-port: " + args[i] + RESET);
                        System.exit(1);
                    }
                }
                default -> {
                    // Any non-flag argument is treated as the WAL path.
                    if (!args[i].startsWith("--")) {
                        walArg = args[i];
                    }
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
            // Interactive CLI — runs until the user types EXIT.
            runCli(engine);
            return;
        }

        // One or both server modes. Start each in turn (both are non-blocking).
        if (serverMode) {
            startTcpServer(engine, port);
        }
        if (webMode) {
            startWebServer(engine, webPort);
        }

        // Block the main thread. The JVM shutdown hook closes the engine.
        System.out.println(GREY + "  Press Ctrl-C to stop." + RESET + "\n");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Server mode helpers
    // -------------------------------------------------------------------------

    /** Starts the TCP server (non-blocking). Prints the connection banner. */
    private static void startTcpServer(StorageEngine engine, int port) {
        NetworkServer server = new NetworkServer(engine, port);
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
    }

    /** Starts the HTTP web server (non-blocking). Prints the URL banner. */
    private static void startWebServer(StorageEngine engine, int port) {
        WebServer web = new WebServer(engine, port);
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
    }

    // -------------------------------------------------------------------------
    // Interactive CLI mode
    // -------------------------------------------------------------------------

    private static void runCli(StorageEngine engine) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print(BOLD + CYAN + "kv> " + RESET);
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    // EOF (e.g., piped input exhausted).
                    break;
                }

                String rawLine = scanner.nextLine().trim();
                if (rawLine.isEmpty()) {
                    continue;
                }

                // Parse command — split on the first whitespace boundary.
                String[] parts   = rawLine.split("\\s+", 2);
                String   command = parts[0].toUpperCase();
                String   rest    = (parts.length > 1) ? parts[1] : "";

                try {
                    switch (command) {
                        case "SET"               -> handleSet(engine, rest);
                        case "GET"               -> handleGet(engine, rest);
                        case "DELETE", "DEL"     -> handleDelete(engine, rest);
                        case "TTL"               -> handleTtl(engine, rest);
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
                } catch (IllegalArgumentException e) {
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

    /**
     * Handles: COMPACT
     */
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
