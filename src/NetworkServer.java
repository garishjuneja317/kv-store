import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NetworkServer — a TCP server that exposes the {@link StorageEngine} over a
 * plain-text line protocol on a configurable port (default 8080).
 *
 * <h2>Protocol</h2>
 * Clients connect via TCP and send newline-terminated command strings. The
 * server responds with one or more newline-terminated reply lines. Every line
 * ending is CRLF ({@code \r\n}) to ensure correct display in Windows telnet.
 *
 * <pre>
 *   Client → Server:  AUTH     &lt;password&gt;
 *                     SET      &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]
 *                     GET      &lt;key&gt;
 *                     DELETE   &lt;key&gt;
 *                     TTL      &lt;key&gt;
 *                     TYPE     &lt;key&gt;
 *                     LPUSH    &lt;key&gt; &lt;element&gt;
 *                     LRANGE   &lt;key&gt; &lt;start&gt; &lt;end&gt;
 *                     SADD     &lt;key&gt; &lt;member&gt;
 *                     SMEMBERS &lt;key&gt;
 *                     COMPACT
 *                     STATS
 *                     HELP
 *                     QUIT
 *
 *   Server → Client:  OK                     (AUTH / SET / SADD succeeded)
 *                     "value"                (GET hit)
 *                     (nil)                  (GET miss / expired)
 *                     DELETED &lt;key&gt;          (DELETE)
 *                     &lt;integer&gt;              (TTL, LPUSH length)
 *                     &lt;type&gt;                 (TYPE)
 *                     1) "e1"\r\n2) "e2"     (LRANGE / SMEMBERS)
 *                     COMPACT OK &lt;ms&gt;        (COMPACT)
 *                     &lt;stats block&gt;          (STATS)
 *                     ERROR &lt;message&gt;        (any error)
 *                     NOAUTH                 (command rejected — authentication required)
 * </pre>
 *
 * <h2>Authentication</h2>
 * When a password is configured via the {@code requirepass} constructor
 * parameter, each connection must send {@code AUTH &lt;password&gt;} before any
 * other command is accepted. Unauthenticated commands receive a
 * {@code NOAUTH Authentication required.} reply.  If no password is set all
 * connections are pre-authenticated.
 *
 * <h2>Concurrency</h2>
 * Each accepted connection is handled in its own Java virtual thread
 * ({@link Thread#ofVirtual()}). Per-connection session state (authenticated
 * flag) lives only on the virtual thread stack — no shared mutable state.
 */
public class NetworkServer {

    private static final Logger LOG = Logger.getLogger(NetworkServer.class.getName());

    private final StorageEngine engine;
    private final int           port;
    private final String        requirepass;   // null → no auth required

    private volatile ServerSocket serverSocket;
    private volatile boolean      running = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code NetworkServer} bound to {@code port}.
     *
     * @param engine      the storage engine to route commands to (must be open)
     * @param port        TCP port to listen on (1–65535)
     * @param requirepass required password, or {@code null} for no authentication
     */
    public NetworkServer(StorageEngine engine, int port, String requirepass) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        this.engine      = engine;
        this.port        = port;
        this.requirepass = (requirepass != null && requirepass.isBlank()) ? null : requirepass;
    }

    /** Creates a {@code NetworkServer} on {@code port} with no authentication. */
    public NetworkServer(StorageEngine engine, int port) {
        this(engine, port, null);
    }

    /** Creates a {@code NetworkServer} on port 8080 with no authentication. */
    public NetworkServer(StorageEngine engine) {
        this(engine, 8080, null);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds the server socket and starts accepting connections on a background
     * virtual-thread daemon.  Returns immediately after the socket is bound.
     *
     * @throws IOException if the socket cannot be bound (e.g., port in use)
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        Thread.ofVirtual().name("kv-net-acceptor").start(this::acceptLoop);
        LOG.info(String.format(
            "[NetworkServer] Listening on port %d — auth=%s",
            port, requirepass != null ? "enabled" : "disabled"));
    }

    /** Stops accepting new connections and closes the server socket. */
    public void shutdown() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try { ss.close(); } catch (IOException e) {
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
    public int getPort() { return port; }

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual()
                      .name("kv-client-" + client.getRemoteSocketAddress())
                      .start(() -> handleClient(client));
            } catch (IOException e) {
                if (running && !serverSocket.isClosed()) {
                    LOG.log(Level.WARNING, "[NetworkServer] Accept error", e);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-connection handler
    // -------------------------------------------------------------------------

    private void handleClient(Socket client) {
        String remote = client.getRemoteSocketAddress().toString();
        LOG.info("[NetworkServer] Client connected: " + remote);

        // Per-connection authentication state — lives on this virtual thread only.
        boolean[] authed = { requirepass == null };

        try (client;
             BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter    out = new PrintWriter(new BufferedOutputStream(client.getOutputStream()), false)) {

            if (requirepass != null) {
                writeLine(out, "KV-STORE ready. Authentication required — send: AUTH <password>");
            } else {
                writeLine(out, "KV-STORE ready. Type HELP for commands.");
            }

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts  = line.split("\\s+", 2);
                String   cmd    = parts[0].toUpperCase();
                String   rest   = (parts.length > 1) ? parts[1] : "";

                // AUTH is always allowed — handle before the auth gate.
                if ("AUTH".equals(cmd)) {
                    if (requirepass == null) {
                        writeLine(out, "OK");       // no password configured
                    } else if (requirepass.equals(rest.trim())) {
                        authed[0] = true;
                        writeLine(out, "OK");
                    } else {
                        writeLine(out, "ERROR invalid password");
                    }
                    continue;
                }

                // QUIT is always allowed.
                if ("QUIT".equals(cmd) || "EXIT".equals(cmd)) {
                    writeLine(out, "BYE");
                    break;
                }

                // Auth gate — all other commands require authentication.
                if (!authed[0]) {
                    writeLine(out, "NOAUTH Authentication required. Please authenticate with: AUTH <password>");
                    continue;
                }

                String response = dispatch(cmd, rest, line);
                writeLine(out, response);
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
     *
     * @param command the upper-cased command token
     * @param rest    the remainder of the line after the command token
     * @param rawLine the original trimmed line (for logging)
     */
    String dispatch(String command, String rest, String rawLine) {
        try {
            return switch (command) {
                case "SET"              -> handleSet(rest);
                case "GET"              -> handleGet(rest);
                case "DELETE", "DEL"    -> handleDelete(rest);
                case "TTL"              -> handleTtl(rest);
                case "TYPE"             -> handleType(rest);
                case "LPUSH"            -> handleLpush(rest);
                case "LRANGE"           -> handleLrange(rest);
                case "SADD"             -> handleSadd(rest);
                case "SMEMBERS"         -> handleSmembers(rest);
                case "COMPACT"          -> handleCompact();
                case "STATS"            -> engine.stats();
                case "HELP"             -> helpText();
                default                 -> "ERROR Unknown command '" + command + "'. Type HELP.";
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "ERROR " + e.getMessage();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[NetworkServer] I/O error processing: " + rawLine, e);
            return "ERROR I/O failure: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // String command handlers
    // -------------------------------------------------------------------------

    /**
     * Handles: SET &lt;key&gt; &lt;value&gt; [EX &lt;seconds&gt;]
     */
    private String handleSet(String rest) throws IOException {
        if (rest.isBlank()) throw new IllegalArgumentException("Usage: SET <key> <value> [EX <seconds>]");
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2) throw new IllegalArgumentException("Usage: SET <key> <value> [EX <seconds>]");
        String key      = kv[0];
        String valueRaw = kv[1];

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
        if (value.isEmpty()) throw new IllegalArgumentException("Value must not be empty.");

        if (ttl > 0) { engine.set(key, value, ttl); return "OK (TTL=" + ttl + "s)"; }
        else         { engine.set(key, value);       return "OK"; }
    }

    /** Handles: GET &lt;key&gt; */
    private String handleGet(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: GET <key>");
        Optional<String> result = engine.get(key);
        return result.map(v -> "\"" + v + "\"").orElse("(nil)");
    }

    /** Handles: DELETE / DEL &lt;key&gt; */
    private String handleDelete(String rest) throws IOException {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: DELETE <key>");
        engine.delete(key);
        return "DELETED " + key;
    }

    /** Handles: TTL &lt;key&gt; */
    private String handleTtl(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: TTL <key>");
        long remaining = engine.ttl(key);
        return switch ((int) Math.max(remaining, -2L)) {
            case -1 -> "-1 (no TTL)";
            case -2 -> "-2 (key not found or expired)";
            default -> String.valueOf(remaining);
        };
    }

    /** Handles: TYPE &lt;key&gt; */
    private String handleType(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: TYPE <key>");
        return engine.type(key);
    }

    // -------------------------------------------------------------------------
    // List command handlers
    // -------------------------------------------------------------------------

    /** Handles: LPUSH &lt;key&gt; &lt;element&gt; */
    private String handleLpush(String rest) throws IOException {
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2 || kv[1].isBlank())
            throw new IllegalArgumentException("Usage: LPUSH <key> <element>");
        int newLen = engine.lpush(kv[0], kv[1]);
        return "(integer) " + newLen;
    }

    /**
     * Handles: LRANGE &lt;key&gt; &lt;start&gt; &lt;end&gt;
     * Returns a Redis-style numbered list reply.
     */
    private String handleLrange(String rest) {
        String[] parts = rest.split("\\s+", 3);
        if (parts.length < 3) throw new IllegalArgumentException("Usage: LRANGE <key> <start> <end>");
        String key = parts[0];
        int start, end;
        try {
            start = Integer.parseInt(parts[1]);
            end   = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("LRANGE start and end must be integers.");
        }
        List<String> elements = engine.lrange(key, start, end);
        if (elements.isEmpty()) return "(empty list)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append("\r\n");
            sb.append(i + 1).append(") \"").append(elements.get(i)).append("\"");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Set command handlers
    // -------------------------------------------------------------------------

    /** Handles: SADD &lt;key&gt; &lt;member&gt; */
    private String handleSadd(String rest) throws IOException {
        String[] kv = rest.split("\\s+", 2);
        if (kv.length < 2 || kv[1].isBlank())
            throw new IllegalArgumentException("Usage: SADD <key> <member>");
        boolean added = engine.sadd(kv[0], kv[1]);
        return "(integer) " + (added ? 1 : 0);
    }

    /** Handles: SMEMBERS &lt;key&gt; */
    private String handleSmembers(String rest) {
        String key = rest.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Usage: SMEMBERS <key>");
        Set<String> members = engine.smembers(key);
        if (members.isEmpty()) return "(empty set)";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String m : new TreeSet<>(members)) {   // sort for deterministic output
            if (i > 1) sb.append("\r\n");
            sb.append(i++).append(") \"").append(m).append("\"");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Other command handlers
    // -------------------------------------------------------------------------

    private String handleCompact() throws IOException {
        long t0 = System.currentTimeMillis();
        engine.compact();
        return "COMPACT OK (" + (System.currentTimeMillis() - t0) + " ms)";
    }

    private static String helpText() {
        return "Available commands:\r\n" +
               "  AUTH     <password>                     authenticate this connection\r\n" +
               "  SET      <key> <value> [EX <seconds>]  store a string with optional TTL\r\n" +
               "  GET      <key>                          retrieve a string value\r\n" +
               "  DELETE   <key>                          remove a key (any type)\r\n" +
               "  TTL      <key>                          remaining TTL (-1=no TTL,-2=missing)\r\n" +
               "  TYPE     <key>                          returns: string | list | set | none\r\n" +
               "  LPUSH    <key> <element>                prepend element to list\r\n" +
               "  LRANGE   <key> <start> <end>           get list slice (0-indexed, -1=last)\r\n" +
               "  SADD     <key> <member>                 add member to set\r\n" +
               "  SMEMBERS <key>                          get all set members (sorted)\r\n" +
               "  COMPACT                                 rewrite WAL eliminating stale entries\r\n" +
               "  STATS                                   show telemetry and disk usage\r\n" +
               "  HELP                                    display this message\r\n" +
               "  QUIT                                    close this connection";
    }

    /**
     * Writes {@code message} to the client, normalising all line endings to
     * {@code \r\n} (CRLF) before appending the final terminating CRLF.
     * This prevents the "staircase" rendering artefact on Windows telnet.
     */
    private static void writeLine(PrintWriter out, String message) {
        String normalised = message.replace("\r\n", "\n").replace("\n", "\r\n");
        out.print(normalised + "\r\n");
        out.flush();
    }
}
