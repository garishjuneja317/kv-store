import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * WebServer — HTTP/1.1 server that exposes the {@link StorageEngine} as a REST
 * API and serves an embedded single-page application (SPA) dashboard.
 *
 * <p><strong>Hackathon compliance note (Track D — Zero-Dependency):</strong>
 * This class imports {@code com.sun.net.httpserver.*}, which is a JDK-internal,
 * <em>demo-grade</em> API bundled with every JDK distribution since Java 6
 * (module {@code jdk.httpserver}).  It is <strong>not</strong> a third-party
 * Maven/Gradle/JAR dependency and therefore satisfies the zero-dependency
 * constraint of Track D.  However, it lives outside the official
 * {@code java.*}/{@code javax.*} namespace and Oracle does not guarantee its
 * stability across future JDK versions.  It is used here solely for
 * demonstration purposes within the hackathon context.
 *
 * <h2>REST Endpoints</h2>
 * <pre>
 *   GET    /                       → serves the embedded SPA dashboard
 *   GET    /api/get?key=X          → {"ok":true,"key":"X","value":"V"}
 *   POST   /api/set                → body: key=X&amp;value=V[&amp;ttl=N]
 *   DELETE /api/delete?key=X       → {"ok":true,"key":"X"}
 *   GET    /api/ttl?key=X          → {"ok":true,"key":"X","ttl":N}
 *   GET    /api/stats              → stats fields as a JSON object
 *   GET    /api/keys               → {"ok":true,"keys":[{"key":"X","ttl":N},...]}
 *   POST   /api/compact            → {"ok":true,"elapsed_ms":N,"live_keys":N}
 * </pre>
 *
 * <h2>JSON</h2>
 * All JSON is hand-built via string concatenation — no JSON library is used.
 * A {@link #jsonEscape(String)} helper correctly escapes control characters,
 * backslashes, and double quotes.
 *
 * <h2>Concurrency</h2>
 * The HTTP server executor is set to {@code Executors.newVirtualThreadPerTaskExecutor()},
 * giving each inbound HTTP request its own virtual thread.  All mutations are
 * still serialised by the {@code ReentrantReadWriteLock} inside
 * {@link StorageEngine}.
 */
public class WebServer {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());

    private static final String MIME_HTML = "text/html; charset=UTF-8";
    private static final String MIME_JSON = "application/json; charset=UTF-8";

    private final StorageEngine engine;
    private final int port;
    private volatile HttpServer httpServer;

    // -------------------------------------------------------------------------
    // Embedded SPA  (INDEX_HTML)
    // -------------------------------------------------------------------------
    // All JSON responses are plain strings. All JavaScript uses single-quoted
    // strings and template literals to avoid conflicts with the Java text-block
    // delimiter. Regex patterns that need \d or \. are written as \\d / \\. in
    // the text block so the resulting String contains a single backslash.
    // -------------------------------------------------------------------------

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <meta name="description" content="KV-Store Dashboard — zero-dependency embedded key-value store management UI">
              <title>KV Store — Dashboard</title>
              <style>
                :root {
                  --bg:          #07090f;
                  --surface:     rgba(255,255,255,0.04);
                  --surface-h:   rgba(255,255,255,0.08);
                  --border:      rgba(255,255,255,0.07);
                  --accent:      #00d4ff;
                  --accent2:     #7c3aed;
                  --success:     #10b981;
                  --error:       #ef4444;
                  --warning:     #f59e0b;
                  --text:        #e2e8f0;
                  --muted:       #64748b;
                  --grad:        linear-gradient(135deg,#00d4ff,#7c3aed);
                  --grad-r:      linear-gradient(135deg,#7c3aed,#00d4ff);
                }
                *{box-sizing:border-box;margin:0;padding:0}
                body{font-family:'Segoe UI',system-ui,-apple-system,sans-serif;
                     background:var(--bg);color:var(--text);min-height:100vh;overflow-x:hidden}
                body::before{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;
                  background:radial-gradient(ellipse 60% 40% at 15% 15%,rgba(0,212,255,.06) 0%,transparent 70%),
                              radial-gradient(ellipse 60% 40% at 85% 85%,rgba(124,58,237,.06) 0%,transparent 70%)}

                /* ── Header ── */
                header{position:sticky;top:0;z-index:100;display:flex;align-items:center;
                       justify-content:space-between;padding:0 2rem;height:60px;
                       background:rgba(7,9,15,.85);backdrop-filter:blur(20px);
                       border-bottom:1px solid var(--border)}
                .logo{display:flex;align-items:center;gap:.6rem;font-size:1.15rem;font-weight:800;
                      background:var(--grad);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}
                .logo-icon{width:30px;height:30px;background:var(--grad);border-radius:8px;
                           display:flex;align-items:center;justify-content:center;
                           font-size:.9rem;-webkit-text-fill-color:#fff;flex-shrink:0}
                .hdr-right{display:flex;align-items:center;gap:1rem;font-size:.8rem;color:var(--muted)}
                .badge{display:flex;align-items:center;gap:.35rem;padding:.2rem .65rem;border-radius:99px;
                       background:rgba(16,185,129,.1);border:1px solid rgba(16,185,129,.25);color:var(--success)}
                .dot{width:6px;height:6px;border-radius:50%;background:var(--success);animation:pulse 2s infinite}
                @keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.5;transform:scale(.8)}}

                /* ── Layout ── */
                main{position:relative;z-index:1;max-width:1440px;margin:0 auto;padding:1.5rem 2rem}

                /* ── Stat cards ── */
                .stat-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));
                           gap:.9rem;margin-bottom:1.5rem}
                .stat-card{background:var(--surface);border:1px solid var(--border);border-radius:16px;
                           padding:1.1rem 1.25rem;position:relative;overflow:hidden;
                           transition:transform .2s,background .2s}
                .stat-card:hover{transform:translateY(-2px);background:var(--surface-h)}
                .stat-card::before{content:'';position:absolute;top:0;left:0;right:0;height:2px;
                                   background:var(--grad);opacity:.55}
                .s-label{font-size:.7rem;text-transform:uppercase;letter-spacing:.1em;color:var(--muted);margin-bottom:.35rem}
                .s-val{font-size:1.8rem;font-weight:700;background:var(--grad);
                       -webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;
                       transition:all .3s;line-height:1.1}
                .s-sub{font-size:.7rem;color:var(--muted);margin-top:.2rem}

                /* ── Content panels ── */
                .content-grid{display:grid;grid-template-columns:1fr 1fr;gap:1.25rem}
                @media(max-width:860px){.content-grid{grid-template-columns:1fr}}
                .panel{background:var(--surface);border:1px solid var(--border);border-radius:18px;overflow:hidden}
                .ph{display:flex;align-items:center;justify-content:space-between;
                    padding:.85rem 1.25rem;border-bottom:1px solid var(--border)}
                .ph-title{font-size:.75rem;font-weight:700;text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}

                /* ── Tabs ── */
                .tab-bar{display:flex;gap:.2rem;padding:.65rem .9rem;border-bottom:1px solid var(--border);overflow-x:auto}
                .tab{padding:.35rem .8rem;border-radius:7px;cursor:pointer;font-size:.78rem;font-weight:600;
                     color:var(--muted);background:transparent;border:none;transition:all .15s;white-space:nowrap}
                .tab:hover{background:var(--surface-h);color:var(--text)}
                .tab.active{background:rgba(0,212,255,.12);color:var(--accent);border:1px solid rgba(0,212,255,.2)}
                .tp{display:none;padding:1.1rem 1.25rem}
                .tp.active{display:block}

                /* ── Form ── */
                .fg{margin-bottom:.9rem}
                label{display:block;font-size:.72rem;color:var(--muted);margin-bottom:.35rem;
                      text-transform:uppercase;letter-spacing:.05em}
                input{width:100%;padding:.6rem .85rem;background:rgba(255,255,255,.04);
                      border:1px solid var(--border);border-radius:9px;color:var(--text);
                      font-size:.88rem;font-family:inherit;transition:border-color .15s,box-shadow .15s;outline:none}
                input:focus{border-color:rgba(0,212,255,.4);box-shadow:0 0 0 3px rgba(0,212,255,.07)}
                input[type=number]{-moz-appearance:textfield}

                /* ── Buttons ── */
                .btn{display:inline-flex;align-items:center;justify-content:center;gap:.4rem;
                     padding:.6rem 1.3rem;border-radius:9px;font-size:.84rem;font-weight:600;
                     cursor:pointer;border:none;transition:all .18s;width:100%}
                .btn-p{background:var(--grad);color:#fff}
                .btn-p:hover{opacity:.85;transform:translateY(-1px);box-shadow:0 4px 18px rgba(0,212,255,.28)}
                .btn-d{background:rgba(239,68,68,.12);color:var(--error);border:1px solid rgba(239,68,68,.25)}
                .btn-d:hover{background:rgba(239,68,68,.22);transform:translateY(-1px)}
                .btn-g{background:rgba(255,255,255,.06);color:var(--text);border:1px solid var(--border)}
                .btn-g:hover{background:rgba(255,255,255,.11)}
                .btn:disabled{opacity:.45;cursor:not-allowed;transform:none!important}

                /* ── Response ── */
                .resp-body{padding:1rem 1.25rem;font-family:'Cascadia Code','Consolas',monospace;
                           font-size:.82rem;line-height:1.65;min-height:140px;white-space:pre-wrap;word-break:break-all}
                .empty{display:flex;flex-direction:column;align-items:center;justify-content:center;
                       padding:2.5rem;color:var(--muted);font-size:.82rem;gap:.5rem}
                .empty-icon{font-size:2rem;opacity:.35}
                .chip{padding:.18rem .55rem;border-radius:6px;font-weight:700;font-size:.75rem}
                .chip-ok{background:rgba(16,185,129,.13);color:var(--success)}
                .chip-err{background:rgba(239,68,68,.13);color:var(--error)}
                .copy-btn{padding:.22rem .55rem;border-radius:6px;font-size:.72rem;cursor:pointer;
                          background:var(--surface-h);border:1px solid var(--border);color:var(--muted);
                          transition:color .15s}
                .copy-btn:hover{color:var(--text)}
                .ri{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--accent);
                    margin-left:.4rem;opacity:0;transition:opacity .3s}
                .ri.flash{animation:blink .45s ease-out forwards}
                @keyframes blink{0%{opacity:1;transform:scale(1.6)}100%{opacity:0;transform:scale(1)}}

                /* ── JSON highlight ── */
                .jk{color:#7dd3fc}  /* key   */
                .jv{color:#86efac}  /* string value */
                .jn{color:#fca5a5}  /* number */
                .jb{color:#c084fc}  /* bool */
                .jl{color:#94a3b8}  /* null */

                /* ── Keys browser ── */
                .keys-list{max-height:260px;overflow-y:auto}
                .ki{display:flex;align-items:center;justify-content:space-between;
                    padding:.55rem 1.25rem;border-bottom:1px solid var(--border);
                    font-size:.82rem;transition:background .12s;cursor:pointer}
                .ki:last-child{border-bottom:none}
                .ki:hover{background:var(--surface-h)}
                .kn{font-family:'Cascadia Code','Consolas',monospace;color:var(--accent)}
                .kt{font-size:.72rem;color:var(--warning)}

                /* ── Compact ── */
                .compact-area{padding:1.75rem 1.25rem;text-align:center}
                .compact-desc{font-size:.83rem;color:var(--muted);margin-bottom:1rem;line-height:1.65}

                /* ── Spinner ── */
                .spin{width:17px;height:17px;border:2px solid rgba(255,255,255,.18);
                      border-top-color:var(--accent);border-radius:50%;animation:rot .55s linear infinite}
                @keyframes rot{to{transform:rotate(360deg)}}

                /* ── Toast ── */
                .toast{position:fixed;bottom:1.75rem;right:1.75rem;padding:.65rem 1.1rem;border-radius:11px;
                       font-size:.84rem;font-weight:500;backdrop-filter:blur(20px);border:1px solid;z-index:9999;
                       transform:translateY(80px);opacity:0;transition:all .3s cubic-bezier(.34,1.56,.64,1)}
                .toast.show{transform:translateY(0);opacity:1}
                .t-ok{background:rgba(16,185,129,.15);border-color:rgba(16,185,129,.35);color:var(--success)}
                .t-err{background:rgba(239,68,68,.15);border-color:rgba(239,68,68,.35);color:var(--error)}

                /* ── Keys count tag ── */
                .ktag{font-size:.75rem;color:var(--muted)}

                /* ── Scrollbar ── */
                ::-webkit-scrollbar{width:5px;height:5px}
                ::-webkit-scrollbar-track{background:transparent}
                ::-webkit-scrollbar-thumb{background:rgba(255,255,255,.09);border-radius:3px}

                /* ── Fade-in ── */
                @keyframes fadeUp{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}
                .fade{animation:fadeUp .22s ease-out}
              </style>
            </head>
            <body>

            <header>
              <div class="logo">
                <div class="logo-icon">⚡</div>
                KV STORE
              </div>
              <div class="hdr-right">
                <span>HTTP :8081</span>
                <div class="badge"><span class="dot"></span>Online</div>
              </div>
            </header>

            <main>

              <!-- Stat cards -->
              <div class="stat-grid">
                <div class="stat-card">
                  <div class="s-label">Live Keys</div>
                  <div class="s-val" id="s-live">—</div>
                  <div class="s-sub">in-memory index</div>
                </div>
                <div class="stat-card">
                  <div class="s-label">WAL Size</div>
                  <div class="s-val" id="s-wal">—</div>
                  <div class="s-sub">on disk</div>
                </div>
                <div class="stat-card">
                  <div class="s-label">SET ops</div>
                  <div class="s-val" id="s-sets">—</div>
                  <div class="s-sub">total writes</div>
                </div>
                <div class="stat-card">
                  <div class="s-label">GET ops</div>
                  <div class="s-val" id="s-gets">—</div>
                  <div class="s-sub">total reads</div>
                </div>
                <div class="stat-card">
                  <div class="s-label">Keys w/ TTL</div>
                  <div class="s-val" id="s-ttl">—</div>
                  <div class="s-sub">expiring</div>
                </div>
                <div class="stat-card">
                  <div class="s-label">Expired</div>
                  <div class="s-val" id="s-exp">—</div>
                  <div class="s-sub">purged keys</div>
                </div>
              </div>

              <!-- Operations + Response -->
              <div class="content-grid" style="margin-bottom:1.25rem">

                <!-- Operations -->
                <div class="panel">
                  <div class="tab-bar">
                    <button class="tab active" id="tb-set"     onclick="switchTab('set',this)">SET</button>
                    <button class="tab"         id="tb-get"     onclick="switchTab('get',this)">GET</button>
                    <button class="tab"         id="tb-delete"  onclick="switchTab('delete',this)">DELETE</button>
                    <button class="tab"         id="tb-ttl"     onclick="switchTab('ttl',this)">TTL</button>
                    <button class="tab"         id="tb-compact" onclick="switchTab('compact',this)">COMPACT</button>
                  </div>

                  <!-- SET panel -->
                  <div class="tp active" id="tp-set">
                    <div class="fg"><label for="set-key">Key</label>
                      <input id="set-key" type="text" placeholder="my-key" autocomplete="off"></div>
                    <div class="fg"><label for="set-value">Value</label>
                      <input id="set-value" type="text" placeholder="my-value" autocomplete="off"></div>
                    <div class="fg"><label for="set-ttl">TTL — seconds (optional)</label>
                      <input id="set-ttl" type="number" placeholder="e.g. 3600" min="1"></div>
                    <button class="btn btn-p" id="btn-set" onclick="doSet()"><span>Set Key</span></button>
                  </div>

                  <!-- GET panel -->
                  <div class="tp" id="tp-get">
                    <div class="fg"><label for="get-key">Key</label>
                      <input id="get-key" type="text" placeholder="my-key" autocomplete="off"
                             onkeydown="if(event.key==='Enter')doGet()"></div>
                    <button class="btn btn-p" id="btn-get" onclick="doGet()"><span>Get Value</span></button>
                  </div>

                  <!-- DELETE panel -->
                  <div class="tp" id="tp-delete">
                    <div class="fg"><label for="del-key">Key</label>
                      <input id="del-key" type="text" placeholder="my-key" autocomplete="off"></div>
                    <button class="btn btn-d" id="btn-delete" onclick="doDelete()"><span>Delete Key</span></button>
                  </div>

                  <!-- TTL panel -->
                  <div class="tp" id="tp-ttl">
                    <div class="fg"><label for="ttl-key">Key</label>
                      <input id="ttl-key" type="text" placeholder="my-key" autocomplete="off"
                             onkeydown="if(event.key==='Enter')doTtl()"></div>
                    <button class="btn btn-g" id="btn-ttl" onclick="doTtl()"><span>Check TTL</span></button>
                  </div>

                  <!-- COMPACT panel -->
                  <div class="tp" id="tp-compact">
                    <div class="compact-area">
                      <p class="compact-desc">
                        Rewrites the WAL, eliminating stale and expired entries.<br>
                        The engine holds an exclusive write lock during compaction.
                      </p>
                      <button class="btn btn-g" id="btn-compact" onclick="doCompact()"
                              style="max-width:200px;margin:0 auto"><span>Run Compaction</span></button>
                    </div>
                  </div>
                </div>

                <!-- Response console -->
                <div class="panel">
                  <div class="ph">
                    <span class="ph-title">Response <span class="ri" id="ri"></span></span>
                    <div style="display:flex;align-items:center;gap:.6rem">
                      <span id="resp-chip"></span>
                      <button class="copy-btn" onclick="copyResp()">Copy</button>
                    </div>
                  </div>
                  <div class="resp-body" id="resp-body">
                    <div class="empty"><div class="empty-icon">◎</div><div>Run a command to see the response</div></div>
                  </div>
                </div>
              </div>

              <!-- Key browser -->
              <div class="panel">
                <div class="ph">
                  <span class="ph-title">Key Browser</span>
                  <div style="display:flex;align-items:center;gap:.6rem">
                    <span class="ktag" id="keys-count"></span>
                    <button class="copy-btn" onclick="loadKeys()">↻ Refresh</button>
                  </div>
                </div>
                <div class="keys-list" id="keys-list">
                  <div class="empty"><div class="empty-icon">🗝</div><div>No keys stored yet</div></div>
                </div>
              </div>

            </main>

            <div class="toast" id="toast"></div>

            <script>
            'use strict';

            // ── State ────────────────────────────────────────────────────────────
            let lastResp = '';

            // ── Toast ────────────────────────────────────────────────────────────
            function toast(msg, isErr) {
              const el = document.getElementById('toast');
              el.textContent = msg;
              el.className = 'toast show ' + (isErr ? 't-err' : 't-ok');
              clearTimeout(el._t);
              el._t = setTimeout(() => { el.className = 'toast ' + (isErr ? 't-err' : 't-ok'); }, 2600);
            }

            // ── Loading state ────────────────────────────────────────────────────
            function busy(id, on) {
              const b = document.getElementById(id);
              if (!b) return;
              if (on) { b.disabled = true; b._h = b.innerHTML; b.innerHTML = '<div class="spin"></div>'; }
              else { b.disabled = false; if (b._h) b.innerHTML = b._h; }
            }

            // ── JSON highlighter (state-machine, no regex backslash sequences) ──
            function escHtml(s) {
              return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
            }
            function hlJson(raw) {
              let s;
              try { s = JSON.stringify(JSON.parse(raw), null, 2); } catch(_) { return escHtml(raw); }
              let out = '', i = 0;
              while (i < s.length) {
                const ch = s[i];
                if (ch === '"') {
                  let j = i + 1;
                  while (j < s.length && s[j] !== '"') { if (s[j] === '\\\\') j++; j++; }
                  const tok = s.substring(i, j + 1);
                  let k = j + 1; while (k < s.length && s[k] === ' ') k++;
                  out += s[k] === ':' ? '<span class="jk">' + escHtml(tok) + '</span>'
                                      : '<span class="jv">' + escHtml(tok) + '</span>';
                  i = j + 1;
                } else if ('0123456789-'.indexOf(ch) >= 0) {
                  let j = i;
                  while (j < s.length && '0123456789.-eE+'.indexOf(s[j]) >= 0) j++;
                  out += '<span class="jn">' + escHtml(s.substring(i, j)) + '</span>'; i = j;
                } else if (s.startsWith('true', i)) {
                  out += '<span class="jb">true</span>'; i += 4;
                } else if (s.startsWith('false', i)) {
                  out += '<span class="jb">false</span>'; i += 5;
                } else if (s.startsWith('null', i)) {
                  out += '<span class="jl">null</span>'; i += 4;
                } else {
                  out += escHtml(ch); i++;
                }
              }
              return out;
            }

            // ── Show response ────────────────────────────────────────────────────
            function showResp(data, ok) {
              lastResp = JSON.stringify(data, null, 2);
              document.getElementById('resp-body').innerHTML =
                '<div class="fade">' + hlJson(JSON.stringify(data)) + '</div>';
              document.getElementById('resp-chip').innerHTML =
                ok ? '<span class="chip chip-ok">200 OK</span>'
                   : '<span class="chip chip-err">Error</span>';
              const ri = document.getElementById('ri');
              ri.className = 'ri flash';
              setTimeout(() => { ri.className = 'ri'; }, 500);
            }

            // ── Tab switching ────────────────────────────────────────────────────
            function switchTab(name, el) {
              document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
              document.querySelectorAll('.tp').forEach(p => p.classList.remove('active'));
              el.classList.add('active');
              document.getElementById('tp-' + name).classList.add('active');
            }

            // ── Operations ───────────────────────────────────────────────────────
            async function doSet() {
              const key = document.getElementById('set-key').value.trim();
              const val = document.getElementById('set-value').value;
              const ttl = document.getElementById('set-ttl').value.trim();
              if (!key || val === '') { toast('Key and Value are required', true); return; }
              busy('btn-set', true);
              try {
                let body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(val);
                if (ttl) body += '&ttl=' + encodeURIComponent(ttl);
                const r = await fetch('/api/set', {method:'POST',
                  headers:{'Content-Type':'application/x-www-form-urlencoded'}, body});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Key set'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { toast(e.message, true); } finally { busy('btn-set', false); }
            }

            async function doGet() {
              const key = document.getElementById('get-key').value.trim();
              if (!key) { toast('Key is required', true); return; }
              busy('btn-get', true);
              try {
                const r = await fetch('/api/get?key=' + encodeURIComponent(key));
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (!d.ok) toast(d.error || 'Not found', true);
              } catch(e) { toast(e.message, true); } finally { busy('btn-get', false); }
            }

            async function doDelete() {
              const key = document.getElementById('del-key').value.trim();
              if (!key) { toast('Key is required', true); return; }
              busy('btn-delete', true);
              try {
                const r = await fetch('/api/delete?key=' + encodeURIComponent(key), {method:'DELETE'});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Key deleted'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { toast(e.message, true); } finally { busy('btn-delete', false); }
            }

            async function doTtl() {
              const key = document.getElementById('ttl-key').value.trim();
              if (!key) { toast('Key is required', true); return; }
              busy('btn-ttl', true);
              try {
                const r = await fetch('/api/ttl?key=' + encodeURIComponent(key));
                const d = await r.json();
                showResp(d, r.ok);
              } catch(e) { toast(e.message, true); } finally { busy('btn-ttl', false); }
            }

            async function doCompact() {
              busy('btn-compact', true);
              try {
                const r = await fetch('/api/compact', {method:'POST'});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Compaction complete (' + d.elapsed_ms + ' ms)'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { toast(e.message, true); } finally { busy('btn-compact', false); }
            }

            // ── Stats ────────────────────────────────────────────────────────────
            async function loadStats() {
              try {
                const d = await (await fetch('/api/stats')).json();
                document.getElementById('s-live').textContent  = d['Live keys']      ?? '—';
                document.getElementById('s-wal').textContent   = d['WAL file size']  ?? '—';
                document.getElementById('s-sets').textContent  = d['SET  ops']       ?? '—';
                document.getElementById('s-gets').textContent  = d['GET  ops']       ?? '—';
                document.getElementById('s-ttl').textContent   = d['Keys with TTL']  ?? '—';
                document.getElementById('s-exp').textContent   = d['Expired purged'] ?? '—';
              } catch(_) {}
            }

            // ── Key browser ──────────────────────────────────────────────────────
            async function loadKeys() {
              try {
                const d = await (await fetch('/api/keys')).json();
                const keys = d.keys || [];
                document.getElementById('keys-count').textContent = keys.length + ' keys';
                const list = document.getElementById('keys-list');
                if (!keys.length) {
                  list.innerHTML = '<div class="empty"><div class="empty-icon">🗝</div><div>No keys stored yet</div></div>';
                  return;
                }
                list.innerHTML = keys.map(k => {
                  const ttlTxt = k.ttl >= 0 ? '<span class="kt">TTL ' + k.ttl + 's</span>' : '';
                  return '<div class="ki" data-key="' + escHtml(k.key) + '">'
                    + '<span class="kn">' + escHtml(k.key) + '</span>' + ttlTxt + '</div>';
                }).join('');
                list.onclick = ev => {
                  const item = ev.target.closest('.ki');
                  if (item) {
                    const key = item.dataset.key;
                    switchTab('get', document.getElementById('tb-get'));
                    document.getElementById('get-key').value = key;
                  }
                };
              } catch(_) {}
            }

            function copyResp() {
              if (!lastResp) return;
              navigator.clipboard.writeText(lastResp).then(() => toast('Copied to clipboard'));
            }

            // ── Init ─────────────────────────────────────────────────────────────
            loadStats();
            loadKeys();
            setInterval(() => { loadStats(); loadKeys(); }, 3000);
            </script>
            </body>
            </html>
            """;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code WebServer} bound to {@code port}.
     *
     * @param engine the storage engine to route API calls to (must already be open)
     * @param port   TCP port for the HTTP server (1–65535)
     */
    public WebServer(StorageEngine engine, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.port   = port;
    }

    /**
     * Creates a {@code WebServer} bound to the default port 8081.
     *
     * @param engine the storage engine to route API calls to
     */
    public WebServer(StorageEngine engine) {
        this(engine, 8081);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds the HTTP server socket and begins accepting requests.
     * Each request is handled on its own virtual thread.
     * Returns as soon as the socket is bound; does not block.
     *
     * @throws IOException if the socket cannot be bound
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Register API routes (most-specific first).
        httpServer.createContext("/api/get",     ex -> safeHandle(ex, this::handleGet));
        httpServer.createContext("/api/set",     ex -> safeHandle(ex, this::handleSet));
        httpServer.createContext("/api/delete",  ex -> safeHandle(ex, this::handleDelete));
        httpServer.createContext("/api/ttl",     ex -> safeHandle(ex, this::handleTtl));
        httpServer.createContext("/api/stats",   ex -> safeHandle(ex, this::handleStats));
        httpServer.createContext("/api/keys",    ex -> safeHandle(ex, this::handleKeys));
        httpServer.createContext("/api/compact", ex -> safeHandle(ex, this::handleCompact));
        // Catch-all: serves SPA for any other path.
        httpServer.createContext("/",            ex -> safeHandle(ex, this::handleRoot));

        httpServer.start();
        LOG.info(String.format(
            "[WebServer] HTTP server listening on port %d (demo-grade: com.sun.net.httpserver)", port));
    }

    /**
     * Stops the HTTP server, waiting up to 1 second for in-flight requests to finish.
     */
    public void shutdown() {
        HttpServer s = httpServer;
        if (s != null) {
            s.stop(1);
            httpServer = null;
            LOG.info("[WebServer] Shutdown complete.");
        }
    }

    /** Returns {@code true} if the server is currently running. */
    public boolean isRunning() {
        return httpServer != null;
    }

    /** Returns the port this server is bound to. */
    public int getPort() {
        return port;
    }

    // -------------------------------------------------------------------------
    // Internal routing scaffold
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface IOHandler {
        void handle(HttpExchange ex) throws IOException;
    }

    /**
     * Wraps a handler with CORS headers, OPTIONS preflight support, and a
     * catch-all exception handler so the server never sends a bare 500 text page.
     */
    private void safeHandle(HttpExchange ex, IOHandler h) {
        try {
            // CORS headers — allow same-origin fetch from the SPA.
            ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            h.handle(ex);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[WebServer] Unhandled error in handler", t);
            try {
                sendError(ex, 500, "Internal server error: " + t.getMessage());
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    /** Serves the embedded SPA, or a JSON 404 for unknown /api/* paths. */
    private void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            sendJson(ex, 404, "{\"ok\":false,\"error\":\"Unknown API endpoint: " + jsonEscape(path) + "\"}");
        } else {
            sendHtml(ex, INDEX_HTML);
        }
    }

    /**
     * {@code GET /api/get?key=X}
     * Returns {@code {"ok":true,"key":"X","value":"V"}} or a 404 JSON error.
     */
    private void handleGet(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key' query parameter\"}");
            return;
        }
        Optional<String> val = engine.get(key);
        if (val.isPresent()) {
            sendJson(ex, 200,
                "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"value\":\"" + jsonEscape(val.get()) + "\"}");
        } else {
            sendJson(ex, 404,
                "{\"ok\":false,\"key\":\"" + jsonEscape(key) + "\",\"error\":\"key not found\"}");
        }
    }

    /**
     * {@code POST /api/set}
     * Body (form-encoded): {@code key=X&value=V[&ttl=N]}
     */
    private void handleSet(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Map<String, String> body = parseFormBody(ex);
        String key    = body.get("key");
        String value  = body.get("value");
        String ttlStr = body.get("ttl");

        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key' in body\"}");
            return;
        }
        if (value == null) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'value' in body\"}");
            return;
        }
        try {
            if (ttlStr != null && !ttlStr.isBlank()) {
                long ttl = Long.parseLong(ttlStr.trim());
                engine.set(key, value, ttl);
                sendJson(ex, 200,
                    "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"ttl\":" + ttl + "}");
            } else {
                engine.set(key, value);
                sendJson(ex, 200,
                    "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\"}");
            }
        } catch (NumberFormatException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"invalid TTL — must be a positive integer\"}");
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /**
     * {@code DELETE /api/delete?key=X}
     */
    private void handleDelete(HttpExchange ex) throws IOException {
        requireMethod(ex, "DELETE");
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key' query parameter\"}");
            return;
        }
        try {
            engine.delete(key);
            sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\"}");
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /**
     * {@code GET /api/ttl?key=X}
     * Returns remaining TTL in seconds: -1 = no TTL, -2 = key not found/expired.
     */
    private void handleTtl(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key' query parameter\"}");
            return;
        }
        long remaining = engine.ttl(key);
        sendJson(ex, 200,
            "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"ttl\":" + remaining + "}");
    }

    /**
     * {@code GET /api/stats}
     * Returns each stats field as a JSON key-value string pair.
     */
    private void handleStats(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        sendJson(ex, 200, buildStatsJson());
    }

    /**
     * {@code GET /api/keys}
     * Returns all live keys sorted alphabetically, with their remaining TTL.
     */
    private void handleKeys(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> snap       = engine.indexSnapshot();
        Map<String, Long>   expirySnap = engine.expirySnapshot();
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder("{\"ok\":true,\"keys\":[");
        boolean first = true;
        // TreeSet gives alphabetical order for stable output.
        for (String key : new TreeSet<>(snap.keySet())) {
            if (!first) sb.append(",");
            first = false;
            Long expMs = expirySnap.get(key);
            long ttlSec = (expMs == null) ? -1L : Math.max(-2L, (expMs - now) / 1000L);
            sb.append("{\"key\":\"").append(jsonEscape(key))
              .append("\",\"ttl\":").append(ttlSec).append("}");
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    /**
     * {@code POST /api/compact}
     * Runs WAL compaction and returns timing and live-key count.
     */
    private void handleCompact(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        long t0 = System.currentTimeMillis();
        engine.compact();
        long elapsed = System.currentTimeMillis() - t0;
        sendJson(ex, 200,
            "{\"ok\":true,\"elapsed_ms\":" + elapsed + ",\"live_keys\":" + engine.liveKeyCount() + "}");
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    /** Sends a 405 JSON error if the request method does not match. */
    private static void requireMethod(HttpExchange ex, String method) throws IOException {
        if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
            throw new IllegalStateException("Method " + ex.getRequestMethod() +
                " not allowed — use " + method);
        }
    }

    /** Parses a URL query string into a name→value map (URL-decoded). */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                if (!k.isEmpty()) map.put(k, v);
            } catch (Exception ignored) {}
        }
        return map;
    }

    /** Reads the request body and parses it as {@code application/x-www-form-urlencoded}. */
    private static Map<String, String> parseFormBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        return parseQuery(new String(raw, StandardCharsets.UTF_8));
    }

    /** Writes a JSON response with the given HTTP status code. */
    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", MIME_JSON);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Writes a full HTML response with status 200. */
    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", MIME_HTML);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Sends a JSON error envelope. */
    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        sendJson(ex, code, "{\"ok\":false,\"error\":\"" + jsonEscape(msg) + "\"}");
    }

    /**
     * Parses the multi-line output of {@link StorageEngine#stats()} into a
     * JSON object.  Each {@code "Label : value"} line becomes one JSON field.
     * Platform-specific line endings ({@code \r\n} on Windows) are handled by
     * {@link String#trim()}.
     */
    private String buildStatsJson() {
        String raw = engine.stats();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("===")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(k)).append("\":\"").append(jsonEscape(v)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Escapes a string for safe inclusion in a JSON string value.
     * Handles {@code "}, {@code \}, control characters (U+0000–U+001F), and
     * CR/LF/TAB explicitly.
     *
     * @param s the raw string (may be null)
     * @return a JSON-safe escaped string (without surrounding quotes)
     */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
