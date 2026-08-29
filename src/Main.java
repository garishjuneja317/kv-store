import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Scanner;

/**
 * Main — interactive CLI entry point for the embedded key-value store.
 *
 * <h2>Supported Commands</h2>
 * <pre>
 *   SET    <key> <value>   Store a value (value may contain spaces)
 *   GET    <key>           Retrieve a value
 *   DELETE <key>           Remove a key (tombstone written to WAL)
 *   COMPACT                Rewrite the WAL, eliminating stale entries
 *   STATS                  Print live telemetry and disk usage
 *   HELP                   Display this help text
 *   EXIT                   Flush, close the engine, and quit
 * </pre>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>The WAL is stored at {@code kv.wal} in the current working directory by
 *       default.  Override with the first CLI argument: {@code java Main /tmp/mydb.wal}</li>
 *   <li>All ANSI colour codes are stripped when stdout is not a terminal (detected
 *       via the {@code NO_COLOR} environment variable or absence of {@code TERM}).</li>
 *   <li>The engine is registered as a JVM shutdown hook so that an ungraceful
 *       {@code Ctrl-C} still flushes the WAL buffer.</li>
 * </ul>
 */
public class Main {

    // -------------------------------------------------------------------------
    // ANSI colour palette
    // -------------------------------------------------------------------------

    private static final boolean COLOUR_ENABLED = detectColour();

    private static final String RESET  = ansi("\u001B[0m");
    private static final String BOLD   = ansi("\u001B[1m");
    private static final String CYAN   = ansi("\u001B[96m");
    private static final String GREEN  = ansi("\u001B[92m");
    private static final String YELLOW = ansi("\u001B[93m");
    private static final String RED    = ansi("\u001B[91m");
    private static final String GREY   = ansi("\u001B[90m");
    private static final String MAGENTA = ansi("\u001B[95m");

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
        "  " + CYAN + "SET    <key> <value>" + RESET + "  — store a key-value pair (value may contain spaces)\n" +
        "  " + CYAN + "GET    <key>"          + RESET + "           — retrieve the value for a key\n" +
        "  " + CYAN + "DELETE <key>"          + RESET + "           — remove a key (writes tombstone to WAL)\n" +
        "  " + CYAN + "COMPACT"               + RESET + "                    — rewrite WAL, eliminating stale entries\n" +
        "  " + CYAN + "STATS"                 + RESET + "                      — show telemetry and disk usage\n" +
        "  " + CYAN + "HELP"                  + RESET + "                       — display this message\n" +
        "  " + CYAN + "EXIT"                  + RESET + "                       — save and quit\n";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println(BANNER);

        // Determine WAL path from optional first argument.
        String walArg = (args != null && args.length > 0) ? args[0] : "kv.wal";

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
            try {
                engine.close();
            } catch (IOException ex) {
                // Suppress — we are shutting down.
            }
        }, "kv-shutdown-hook"));

        // -------------------------------------------------------------------------
        // REPL
        // -------------------------------------------------------------------------

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
                String[] parts = rawLine.split("\\s+", 2);
                String command = parts[0].toUpperCase();
                String rest    = (parts.length > 1) ? parts[1] : "";

                try {
                    switch (command) {
                        case "SET"    -> handleSet(engine, rest);
                        case "GET"    -> handleGet(engine, rest);
                        case "DELETE", "DEL" -> handleDelete(engine, rest);
                        case "COMPACT" -> handleCompact(engine);
                        case "STATS"  -> handleStats(engine);
                        case "HELP"   -> System.out.println(HELP_TEXT);
                        case "EXIT", "QUIT" -> {
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
            // Ensure close even if an unexpected exception escapes the loop.
            try {
                engine.close();
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Command handlers
    // -------------------------------------------------------------------------

    /**
     * Handles: SET <key> <value>
     * The value is everything after the first whitespace token following SET,
     * so values with embedded spaces are stored verbatim.
     */
    private static void handleSet(StorageEngine engine, String rest) throws IOException {
        if (rest.isBlank()) {
            throw new IllegalArgumentException("Usage: SET <key> <value>");
        }
        // Split into key + value (value may contain spaces).
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2) {
            throw new IllegalArgumentException(
                "Usage: SET <key> <value>  — both key and value are required.");
        }
        String key   = kv[0];
        String value = kv[1];

        engine.set(key, value);
        System.out.println(GREEN + "OK" + RESET + "  " +
            GREY + key + " → " + truncate(value, 80) + RESET);
    }

    /**
     * Handles: GET <key>
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
     * Handles: DELETE <key>
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
