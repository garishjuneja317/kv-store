import java.io.*;
import java.net.*;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NetworkServer — a TCP server that exposes the {@link StorageEngine} over a
 * plain-text line protocol on a configurable port (default 8080).
 *
 * <h2>Protocol</h2>
 * Clients connect via TCP and send newline-terminated command strings.  The
 * server responds with a newline-terminated reply.  No authentication is
 * performed — this server is intended for trusted, local deployments.
 *
 * <pre>
 *   Client → Server:  SET  key value [EX seconds]
 *                     GET  key
 *                     DELETE key
 *                     TTL  key
 *                     COMPACT
 *                     STATS
 *                     HELP
 *                     QUIT
 *
 *   Server → Client:  OK              (SET succeeded)
 *                     "value"         (GET hit)
 *                     (nil)           (GET miss / expired)
 *                     DELETED         (DELETE)
 *                     &lt;integer&gt;       (TTL in seconds; -1 = no TTL; -2 = not found)
 *                     COMPACT OK      (COMPACT)
 *                     &lt;stats block&gt;   (STATS)
 *                     ERROR &lt;message&gt; (any error)
 * </pre>
 *
 * <h2>Concurrency</h2>
 * Each accepted connection is handled in its own Java virtual thread
 * ({@link Thread#ofVirtual()}), so the server scales to thousands of
 * simultaneous clients without a fixed thread pool.
 *
 * <h2>Lifecycle</h2>
 * Call {@link #start()} to begin accepting connections in a background
 * virtual-thread daemon.  Call {@link #shutdown()} to stop accepting new
 * connections; existing handlers will complete their current command.
 */
public class NetworkServer {

    private static final Logger LOG = Logger.getLogger(NetworkServer.class.getName());

    private final StorageEngine engine;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * Creates a {@code NetworkServer} bound to {@code port}.
     *
     * @param engine the storage engine to route commands to (must already be open)
     * @param port   TCP port to listen on (1–65535)
     */
    public NetworkServer(StorageEngine engine, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        this.engine = engine;
        this.port   = port;
    }

    /**
     * Creates a {@code NetworkServer} bound to the default port 8080.
     *
     * @param engine the storage engine to route commands to
     */
    public NetworkServer(StorageEngine engine) {
        this(engine, 8080);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds the server socket and starts accepting connections on a background
     * virtual-thread daemon.  Returns immediately after the socket is bound.
     *
     * @throws IOException if the socket cannot be bound (e.g., port already in use)
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;

        Thread.ofVirtual()
              .name("kv-net-acceptor")
              .start(this::acceptLoop);

        LOG.info(String.format("[NetworkServer] Listening on port %d — virtual-thread per connection", port));
    }

    /**
     * Stops accepting new connections and closes the server socket.
     * Already-running handlers are allowed to finish their current command.
     */
    public void shutdown() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[NetworkServer] Error closing server socket", e);
            }
        }
        LOG.info("[NetworkServer] Shutdown complete.");
    }

    /** Returns {@code true} if the server is currently accepting connections. */
    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    /** Returns the port this server is bound to. */
    public int getPort() {
        return port;
    }

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                // Spawn one virtual thread per connection.
                Thread.ofVirtual()
                      .name("kv-client-" + client.getRemoteSocketAddress())
                      .start(() -> handleClient(client));
            } catch (IOException e) {
                if (running && !serverSocket.isClosed()) {
                    LOG.log(Level.WARNING, "[NetworkServer] Accept error", e);
                }
                // Otherwise the server is shutting down — exit the loop.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-connection handler
    // -------------------------------------------------------------------------

    private void handleClient(Socket client) {
        String remote = client.getRemoteSocketAddress().toString();
        LOG.info("[NetworkServer] Client connected: " + remote);
        try (client;
             BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter    out = new PrintWriter(new BufferedOutputStream(client.getOutputStream()), false)) {

            // Send a brief greeting with explicit CRLF for telnet compatibility.
            writeLine(out, "KV-STORE ready. Type HELP for commands.");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String response = dispatch(line);
                writeLine(out, response);

                // QUIT disconnects cleanly.
                if ("QUIT".equalsIgnoreCase(line.split("\\s+", 2)[0])) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "[NetworkServer] Client disconnected: " + remote, e);
        }
        LOG.info("[NetworkServer] Client disconnected: " + remote);
    }

    // -------------------------------------------------------------------------
    // Command dispatcher
    // -------------------------------------------------------------------------

    /**
     * Parses and executes a single text command, returning the plain-text response.
     * All responses are single lines (STATS may contain embedded newlines — the
     * client should read until it sees the {@code "==="} footer line).
     *
     * @param rawLine raw input line from the client (already trimmed)
     * @return the response to send back
     */
    String dispatch(String rawLine) {
        String[] parts   = rawLine.split("\\s+", 2);
        String   command = parts[0].toUpperCase();
        String   rest    = (parts.length > 1) ? parts[1] : "";

        try {
            return switch (command) {
                case "SET"              -> handleSet(rest);
                case "GET"              -> handleGet(rest);
                case "DELETE", "DEL"    -> handleDelete(rest);
                case "TTL"              -> handleTtl(rest);
                case "COMPACT"          -> handleCompact();
                case "STATS"            -> engine.stats();
                case "HELP"             -> helpText();
                case "QUIT", "EXIT"     -> "BYE";
                default                 -> "ERROR Unknown command '" + command + "'. Type HELP.";
            };
        } catch (IllegalArgumentException e) {
            return "ERROR " + e.getMessage();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[NetworkServer] I/O error processing command: " + rawLine, e);
            return "ERROR I/O failure: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Command implementations
    // -------------------------------------------------------------------------

    /**
     * Handles: SET &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]
     *
     * <p>The value is everything after the first token, optionally followed by
     * the literal token {@code EX} and a positive integer TTL.
     * Example: {@code SET mykey hello world EX 30}
     */
    private String handleSet(String rest) throws IOException {
        if (rest.isBlank()) {
            throw new IllegalArgumentException("Usage: SET <key> <value> [EX <seconds>]");
        }
        // Split: key + "value [EX seconds]"
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2) {
            throw new IllegalArgumentException("Usage: SET <key> <value> [EX <seconds>]");
        }
        String key      = kv[0];
        String valueRaw = kv[1];

        // Detect trailing "EX <n>" (case-insensitive).
        // Pattern: value may contain spaces; EX clause is always the last two tokens.
        String[] tokens  = valueRaw.split("\\s+");
        long     ttl     = 0;
        String   value;

        if (tokens.length >= 3 && "EX".equalsIgnoreCase(tokens[tokens.length - 2])) {
            try {
                ttl = Long.parseLong(tokens[tokens.length - 1]);
                if (ttl <= 0) throw new NumberFormatException("non-positive");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "EX value must be a positive integer, got: " + tokens[tokens.length - 1]);
            }
            // Reconstruct value without the trailing "EX <n>".
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
            return "OK (TTL=" + ttl + "s)";
        } else {
            engine.set(key, value);
            return "OK";
        }
    }

    /** Handles: GET &lt;key&gt; */
    private String handleGet(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: GET <key>");
        }
        Optional<String> result = engine.get(key);
        return result.map(v -> "\"" + v + "\"").orElse("(nil)");
    }

    /** Handles: DELETE &lt;key&gt; */
    private String handleDelete(String rest) throws IOException {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: DELETE <key>");
        }
        engine.delete(key);
        return "DELETED " + key;
    }

    /** Handles: TTL &lt;key&gt; */
    private String handleTtl(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Usage: TTL <key>");
        }
        long remaining = engine.ttl(key);
        return switch ((int) Math.max(remaining, -2L)) {
            case -1 -> "-1 (no TTL)";
            case -2 -> "-2 (key not found or expired)";
            default -> String.valueOf(remaining);
        };
    }

    /** Handles: COMPACT */
    private String handleCompact() throws IOException {
        long t0 = System.currentTimeMillis();
        engine.compact();
        return "COMPACT OK (" + (System.currentTimeMillis() - t0) + " ms)";
    }

    private static String helpText() {
        // Use explicit \r\n so every line is properly terminated for telnet clients.
        return "Available commands:\r\n" +
               "  SET    <key> <value> [EX <seconds>]  store a key-value pair with optional TTL\r\n" +
               "  GET    <key>                          retrieve a value\r\n" +
               "  DELETE <key>                          remove a key\r\n" +
               "  TTL    <key>                          get remaining TTL in seconds (-1=no TTL, -2=missing)\r\n" +
               "  COMPACT                               rewrite WAL eliminating stale entries\r\n" +
               "  STATS                                 show telemetry and disk usage\r\n" +
               "  HELP                                  display this message\r\n" +
               "  QUIT                                  close this connection";
    }

    /**
     * Writes {@code message} to the client, normalising all line endings to
     * {@code \r\n} (CRLF) before appending the final terminating CRLF.
     *
     * <p>This ensures correct display on Windows {@code telnet} clients, which
     * require Carriage Return + Line Feed; a bare {@code \n} causes the
     * well-known "staircase" rendering artefact.
     *
     * @param out     the {@link PrintWriter} connected to the client socket
     * @param message the response string (may contain embedded {@code \n})
     */
    private static void writeLine(PrintWriter out, String message) {
        // Normalise: collapse any existing \r\n → \n first, then expand all \n → \r\n.
        String normalised = message.replace("\r\n", "\n").replace("\n", "\r\n");
        out.print(normalised + "\r\n");
        out.flush();
    }
}
