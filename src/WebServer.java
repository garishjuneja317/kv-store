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
 * This class imports {@code com.sun.net.httpserver.*}, a JDK-internal,
 * <em>demo-grade</em> API bundled with every JDK since Java 6 (module
 * {@code jdk.httpserver}).  It is <strong>not</strong> a third-party
 * Maven/Gradle/JAR dependency and therefore satisfies the zero-dependency
 * constraint of Track D.  It is used here solely for demonstration purposes.
 *
 * <h2>Authentication</h2>
 * When a password is supplied to the constructor, all {@code /api/} endpoints
 * require an {@code Authorization: Bearer &lt;password&gt;} request header.
 * Requests without a valid header receive a 401 JSON error. The embedded SPA
 * detects 401 responses, prompts for the password via an in-page modal, and
 * retains it in a JS variable for subsequent calls.
 *
 * <h2>REST Endpoints</h2>
 * <pre>
 *   GET    /                       → embedded SPA dashboard
 *   GET    /api/get?key=X          → {"ok":true,"key":"X","value":"V","type":"string"}
 *   POST   /api/set                → body: key=X&amp;value=V[&amp;ttl=N]
 *   DELETE /api/delete?key=X       → {"ok":true,"key":"X"}
 *   GET    /api/ttl?key=X          → {"ok":true,"key":"X","ttl":N}
 *   GET    /api/type?key=X         → {"ok":true,"key":"X","type":"string|list|set|none"}
 *   GET    /api/stats              → stats fields as JSON
 *   GET    /api/keys               → {"ok":true,"keys":[{"key":"X","type":"string","ttl":N},...]}
 *   POST   /api/compact            → {"ok":true,"elapsed_ms":N,"live_keys":N}
 *   POST   /api/lpush              → body: key=X&amp;value=V
 *   GET    /api/lrange?key=X[&amp;start=0&amp;end=-1]
 *   POST   /api/sadd               → body: key=X&amp;value=V
 *   GET    /api/smembers?key=X
 * </pre>
 *
 * <h2>JSON</h2>
 * All JSON is hand-built via string concatenation — no JSON library is used.
 * {@link #jsonEscape(String)} correctly escapes control characters, backslashes,
 * and double-quotes.
 */
public class WebServer {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());

    private static final String MIME_HTML = "text/html; charset=UTF-8";
    private static final String MIME_JSON = "application/json; charset=UTF-8";

    private final StorageEngine engine;
    private final int           port;
    private final String        requirepass;   // null → no auth

    private volatile HttpServer httpServer;

    // -------------------------------------------------------------------------
    // Embedded SPA
    // -------------------------------------------------------------------------

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>KV Store — Dashboard</title>
              <style>
                :root {
                  --bg: #f4f3ed;
                  --surface: #ffffff;
                  --surface-h: #eae9e1;
                  --border: #dcdbd3;
                  --text: #262626;
                  --muted: #737373;
                  --accent: #ffa116; 
                  --accent-fg: #ffffff;
                  --success: #2cbb5d;
                  --error: #ef4743;
                  --warning: #ffc01e;
                  --shadow: 0 8px 24px rgba(0,0,0,0.15);
                  --radius: 8px;
                }
                [data-theme="dark"] {
                  --bg: #1a1a1a;
                  --surface: #282828;
                  --surface-h: #3e3e3e;
                  --border: #404040;
                  --text: #eff1f6;
                  --muted: #8c8c8c;
                  --accent: #ffa116; 
                  --accent-fg: #1a1a1a;
                  --shadow: 0 4px 12px rgba(0,0,0,0.4);
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                  background: var(--bg); color: var(--text); min-height: 100vh; overflow-x: hidden;
                  transition: background 0.2s, color 0.2s;
                }
                
                header {
                  position: sticky; top: 0; z-index: 100; display: flex; align-items: center;
                  justify-content: space-between; padding: 0 2rem; height: 60px;
                  background: var(--surface); border-bottom: 1px solid var(--border);
                  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
                }
                .logo {
                  display: flex; align-items: center; gap: 0.75rem; font-size: 1.2rem; font-weight: 700;
                }
                .logo-icon {
                  width: 28px; height: 28px; background: var(--accent); color: var(--accent-fg);
                  display: flex; align-items: center; justify-content: center; font-size: 1.2rem;
                  border-radius: 6px; font-weight: bold;
                }
                .hdr-right { display: flex; align-items: center; gap: 1.25rem; font-size: 0.85rem; font-weight: 600; }
                .badge {
                  display: flex; align-items: center; gap: 0.4rem; padding: 0.25rem 0.75rem;
                  background: rgba(44, 187, 93, 0.1); border: 1px solid var(--success); color: var(--success);
                  border-radius: 12px;
                }
                .dot { width: 8px; height: 8px; background: var(--success); border-radius: 50%; }
                
                .theme-toggle {
                  background: none; border: 1px solid var(--border); cursor: pointer; padding: 0.35rem 0.75rem;
                  color: var(--text); font-weight: 600; background: var(--surface); border-radius: var(--radius);
                  transition: background 0.2s;
                }
                .theme-toggle:hover { background: var(--surface-h); }

                main { position: relative; z-index: 1; max-width: 1440px; margin: 0 auto; padding: 2rem; }

                .stat-grid {
                  display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
                  gap: 1.25rem; margin-bottom: 2rem;
                }
                .stat-card {
                  background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow);
                  padding: 1.25rem; transition: transform 0.2s; border-radius: var(--radius);
                }
                .stat-card:hover { transform: translateY(-2px); }
                .s-label { font-size: 0.75rem; text-transform: uppercase; font-weight: 600; color: var(--muted); margin-bottom: 0.5rem; }
                .s-val { font-size: 1.75rem; font-weight: 700; color: var(--text); line-height: 1.2; font-family: monospace; }
                .s-sub { font-size: 0.7rem; color: var(--muted); margin-top: 0.25rem; }

                .content-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 2rem; margin-bottom: 2rem; }
                @media(max-width: 860px) { .content-grid { grid-template-columns: 1fr; } }
                
                .panel { background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow); border-radius: var(--radius); overflow: hidden; }
                .ph {
                  display: flex; align-items: center; justify-content: space-between;
                  padding: 1rem 1.25rem; border-bottom: 1px solid var(--border); background: var(--surface-h);
                }
                .ph-title { font-size: 0.85rem; font-weight: 600; text-transform: uppercase; }

                .tab-bar {
                  display: flex; gap: 0; border-bottom: 1px solid var(--border); overflow-x: auto; overflow-y: hidden; flex-wrap: nowrap;
                  scrollbar-width: none;
                }
                .tab-bar::-webkit-scrollbar { display: none; }
                
                .tab {
                  padding: 0.85rem 1.2rem; cursor: pointer; font-size: 0.8rem; font-weight: 600;
                  color: var(--muted); background: var(--surface); border: none; border-right: 1px solid var(--border);
                  font-family: inherit; transition: background 0.1s, color 0.1s; text-transform: uppercase;
                  white-space: nowrap;
                }
                .tab:hover { background: var(--surface-h); color: var(--text); }
                .tab.active { background: var(--bg); color: var(--accent); border-bottom: 2px solid var(--accent); margin-bottom: -1px; }
                
                .tp { display: none; padding: 1.5rem; background: var(--bg); }
                .tp.active { display: block; }

                .fg { margin-bottom: 1.25rem; }
                label {
                  display: block; font-size: 0.75rem; color: var(--text); font-weight: 600;
                  margin-bottom: 0.5rem; text-transform: uppercase;
                }
                input {
                  width: 100%; padding: 0.75rem; background: var(--surface);
                  border: 1px solid var(--border); color: var(--text); border-radius: 6px;
                  font-size: 0.9rem; font-family: inherit; outline: none; transition: border-color 0.2s;
                }
                input:focus { border-color: var(--accent); }

                .btn {
                  display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem;
                  padding: 0.75rem 1.5rem; font-size: 0.85rem; font-weight: 600; text-transform: uppercase;
                  cursor: pointer; border: none; border-radius: 6px; font-family: inherit;
                  transition: opacity 0.2s; width: 100%; 
                }
                .btn:hover { opacity: 0.9; }
                .btn-p { background: var(--accent); color: var(--accent-fg); }
                .btn-d { background: rgba(239, 71, 67, 0.1); color: var(--error); border: 1px solid var(--error); }
                .btn-g { background: var(--surface-h); color: var(--text); border: 1px solid var(--border); }
                .btn:disabled { opacity: 0.5; cursor: not-allowed; }

                .resp-body {
                  padding: 1.5rem; font-size: 0.85rem; line-height: 1.6; min-height: 160px;
                  white-space: pre-wrap; word-break: break-all; background: var(--bg); font-family: monospace;
                }
                .empty {
                  display: flex; flex-direction: column; align-items: center; justify-content: center;
                  padding: 3rem; color: var(--muted); font-size: 0.85rem; gap: 1rem; font-weight: 600; text-transform: uppercase;
                }
                .empty-icon { font-size: 2.5rem; opacity: 0.3; }
                
                .chip { padding: 0.25rem 0.5rem; border-radius: 4px; font-weight: 700; font-size: 0.75rem; text-transform: uppercase; }
                .chip-ok { background: rgba(44, 187, 93, 0.15); color: var(--success); }
                .chip-err { background: rgba(239, 71, 67, 0.15); color: var(--error); }
                
                .copy-btn {
                  padding: 0.35rem 0.75rem; font-size: 0.75rem; font-weight: 600; cursor: pointer; text-transform: uppercase;
                  background: var(--surface); border: 1px solid var(--border); color: var(--text); border-radius: 4px;
                }
                
                .ri {
                  display: inline-block; width: 8px; height: 8px; background: var(--accent); border-radius: 50%;
                  margin-left: 0.5rem; opacity: 0; 
                }
                .ri.flash { animation: blink 0.5s ease-out forwards; }
                @keyframes blink { 0% { opacity: 1; } 100% { opacity: 0; } }
                
                .jk { color: var(--accent); font-weight: bold; } 
                .jv { color: var(--success); } 
                .jn { color: var(--error); } 
                .jb { color: var(--warning); font-weight: bold; } 
                .jl { color: var(--muted); }

                .keys-list { max-height: 300px; overflow-y: auto; background: var(--bg); }
                .ki {
                  display: flex; align-items: center; justify-content: space-between;
                  padding: 0.75rem 1.25rem; border-bottom: 1px solid var(--border);
                  font-size: 0.85rem; cursor: pointer; font-weight: 600;
                }
                .ki:last-child { border-bottom: none; }
                .ki:hover { background: var(--surface-h); }
                .kn { color: var(--text); font-family: monospace; }
                .kt { font-size: 0.75rem; color: var(--muted); }
                .ktype { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; margin-left: 0.5rem; text-transform: uppercase; }
                .ktype-string { background: var(--surface-h); color: var(--text); }
                .ktype-list { background: rgba(255, 192, 30, 0.15); color: var(--warning); }
                .ktype-set { background: rgba(255, 161, 22, 0.15); color: var(--accent); }

                .compact-area { padding: 3rem 1.5rem; text-align: center; }
                .compact-desc { font-size: 0.85rem; color: var(--muted); margin-bottom: 1.5rem; line-height: 1.6; font-weight: 600; text-transform: uppercase; }
                
                .spin {
                  width: 18px; height: 18px; border: 2px solid var(--muted);
                  border-top-color: var(--accent); border-radius: 50%; animation: rot 0.6s linear infinite; margin: 0 auto;
                }
                @keyframes rot { to { transform: rotate(360deg); } }

                .toast {
                  position: fixed; bottom: 2rem; right: 2rem; padding: 1rem 1.5rem; border-radius: var(--radius);
                  font-size: 0.85rem; font-weight: 600; background: var(--surface); color: var(--text); border: 1px solid var(--border);
                  z-index: 9999; transform: translateY(100px); opacity: 0; transition: all 0.2s; box-shadow: var(--shadow);
                }
                .toast.show { transform: translateY(0); opacity: 1; }

                .overlay {
                  position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 9000;
                  display: flex; align-items: center; justify-content: center;
                }
                .overlay.hidden { display: none; }
                .auth-card {
                  background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow);
                  padding: 3rem; width: min(420px, 90vw); text-align: center; border-radius: var(--radius);
                }
                .auth-title { font-size: 1.25rem; font-weight: 700; margin-bottom: 0.5rem; }
                .auth-sub { font-size: 0.85rem; color: var(--muted); margin-bottom: 1.5rem; }
                
                .pwd-wrapper { position: relative; margin-bottom: 1.5rem; }
                .auth-card input { margin-bottom: 0; padding-right: 4.5rem; text-align: left; }
                .pwd-toggle {
                  position: absolute; right: 0.4rem; top: 50%; transform: translateY(-50%);
                  background: var(--surface-h); border: 1px solid var(--border); border-radius: 4px;
                  padding: 0.3rem 0.6rem; cursor: pointer; font-size: 0.7rem; font-weight: 700;
                  color: var(--text); transition: background 0.1s;
                }
                .pwd-toggle:hover { background: var(--border); }

                .ktag { font-size: 0.85rem; color: var(--text); font-weight: 600; }

                ::-webkit-scrollbar { width: 8px; height: 8px; }
                ::-webkit-scrollbar-track { background: var(--bg); }
                ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 4px; }
                
                .fade { animation: fadeUp 0.2s ease-out; }
                @keyframes fadeUp { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
              </style>
            </head>
            <body>

            <div class="overlay hidden" id="auth-overlay">
              <div class="auth-card">
                <div class="auth-title">System Locked</div>
                <div class="auth-sub">Enter Access Token</div>
                <div class="fg">
                  <div class="pwd-wrapper">
                    <input id="auth-input" type="password" placeholder="Database Password" onkeydown="if(event.key==='Enter')submitAuth()">
                    <button type="button" class="pwd-toggle" id="pwd-toggle" onclick="togglePwd()">SHOW</button>
                  </div>
                </div>
                <button class="btn btn-p" onclick="submitAuth()"><span>Authorize</span></button>
              </div>
            </div>

            <header>
              <div class="logo"><div class="logo-icon">{ }</div>KV-DB</div>
              <div class="hdr-right">
                <button class="theme-toggle" id="theme-btn" onclick="toggleTheme()">Dark</button>
                <span id="hdr-auth" style="display:none;color:var(--warning);">[AUTH]</span>
                <span>PORT 8081</span>
                <div class="badge"><span class="dot"></span>Online</div>
              </div>
            </header>

            <main>
              <div class="stat-grid">
                <div class="stat-card"><div class="s-label">Live Keys</div><div class="s-val" id="s-live">—</div><div class="s-sub">In-Memory</div></div>
                <div class="stat-card"><div class="s-label">String Keys</div><div class="s-val" id="s-str">—</div><div class="s-sub">Scalars</div></div>
                <div class="stat-card"><div class="s-label">List Keys</div><div class="s-val" id="s-lst">—</div><div class="s-sub">Collections</div></div>
                <div class="stat-card"><div class="s-label">Set Keys</div><div class="s-val" id="s-set">—</div><div class="s-sub">Uniques</div></div>
                <div class="stat-card"><div class="s-label">WAL Size</div><div class="s-val" id="s-wal">—</div><div class="s-sub">Bytes Disk</div></div>
                <div class="stat-card"><div class="s-label">Writes</div><div class="s-val" id="s-sets">—</div><div class="s-sub">Mutations</div></div>
                <div class="stat-card"><div class="s-label">Reads</div><div class="s-val" id="s-gets">—</div><div class="s-sub">Queries</div></div>
                <div class="stat-card"><div class="s-label">Purged</div><div class="s-val" id="s-exp">—</div><div class="s-sub">Evicted TTL</div></div>
              </div>

              <div class="content-grid">
                <div class="panel">
                  <div class="tab-bar">
                    <button class="tab active" id="tb-set" onclick="switchTab('set',this)">SET</button>
                    <button class="tab" id="tb-get" onclick="switchTab('get',this)">GET</button>
                    <button class="tab" id="tb-delete" onclick="switchTab('delete',this)">DEL</button>
                    <button class="tab" id="tb-ttl" onclick="switchTab('ttl',this)">TTL</button>
                    <button class="tab" id="tb-lpush" onclick="switchTab('lpush',this)">LPUSH</button>
                    <button class="tab" id="tb-lrange" onclick="switchTab('lrange',this)">LRANGE</button>
                    <button class="tab" id="tb-sadd" onclick="switchTab('sadd',this)">SADD</button>
                    <button class="tab" id="tb-smembers" onclick="switchTab('smembers',this)">SMEM</button>
                    <button class="tab" id="tb-compact" onclick="switchTab('compact',this)">WAL</button>
                  </div>

                  <div class="tp active" id="tp-set">
                    <div class="fg"><label>Key Identifier</label><input id="set-key" type="text" placeholder="key_name"></div>
                    <div class="fg"><label>Payload Value</label><input id="set-value" type="text" placeholder="data_string"></div>
                    <div class="fg"><label>Expiration TTL (Sec)</label><input id="set-ttl" type="number" placeholder="Optional" min="1"></div>
                    <button class="btn btn-p" id="btn-set" onclick="doSet()"><span>Execute SET</span></button>
                  </div>

                  <div class="tp" id="tp-get">
                    <div class="fg"><label>Key Identifier</label><input id="get-key" type="text" placeholder="key_name" onkeydown="if(event.key==='Enter')doGet()"></div>
                    <button class="btn btn-p" id="btn-get" onclick="doGet()"><span>Execute GET</span></button>
                  </div>

                  <div class="tp" id="tp-delete">
                    <div class="fg"><label>Key Identifier</label><input id="del-key" type="text" placeholder="key_name"></div>
                    <button class="btn btn-d" id="btn-delete" onclick="doDelete()"><span>Execute DEL</span></button>
                  </div>

                  <div class="tp" id="tp-ttl">
                    <div class="fg"><label>Key Identifier</label><input id="ttl-key" type="text" placeholder="key_name" onkeydown="if(event.key==='Enter')doTtl()"></div>
                    <button class="btn btn-g" id="btn-ttl" onclick="doTtl()"><span>Check TTL</span></button>
                  </div>

                  <div class="tp" id="tp-lpush">
                    <div class="fg"><label>List Key</label><input id="lp-key" type="text" placeholder="list_name"></div>
                    <div class="fg"><label>Prepend Element</label><input id="lp-val" type="text" placeholder="value"></div>
                    <button class="btn btn-p" id="btn-lpush" onclick="doLpush()"><span>Execute LPUSH</span></button>
                  </div>

                  <div class="tp" id="tp-lrange">
                    <div class="fg"><label>List Key</label><input id="lr-key" type="text" placeholder="list_name"></div>
                    <div style="display:grid;grid-template-columns:1fr 1fr;gap:1rem">
                      <div class="fg"><label>Start Index</label><input id="lr-start" type="number" value="0"></div>
                      <div class="fg"><label>End Index</label><input id="lr-end" type="number" value="-1"></div>
                    </div>
                    <button class="btn btn-g" id="btn-lrange" onclick="doLrange()"><span>Execute LRANGE</span></button>
                  </div>

                  <div class="tp" id="tp-sadd">
                    <div class="fg"><label>Set Key</label><input id="sa-key" type="text" placeholder="set_name"></div>
                    <div class="fg"><label>Insert Member</label><input id="sa-val" type="text" placeholder="value"></div>
                    <button class="btn btn-p" id="btn-sadd" onclick="doSadd()"><span>Execute SADD</span></button>
                  </div>

                  <div class="tp" id="tp-smembers">
                    <div class="fg"><label>Set Key</label><input id="sm-key" type="text" placeholder="set_name" onkeydown="if(event.key==='Enter')doSmembers()"></div>
                    <button class="btn btn-g" id="btn-smembers" onclick="doSmembers()"><span>Execute SMEMBERS</span></button>
                  </div>

                  <div class="tp" id="tp-compact">
                    <div class="compact-area">
                      <p class="compact-desc">Trigger a manual Write-Ahead Log rewrite.<br>Engine holds exclusive write lock.</p>
                      <button class="btn btn-g" id="btn-compact" onclick="doCompact()"><span>Compact WAL</span></button>
                    </div>
                  </div>
                </div>

                <div class="panel">
                  <div class="ph">
                    <span class="ph-title">System Output <span class="ri" id="ri"></span></span>
                    <div style="display:flex;align-items:center;gap:1rem">
                      <span id="resp-chip"></span>
                      <button class="copy-btn" onclick="copyResp()">Copy</button>
                    </div>
                  </div>
                  <div class="resp-body" id="resp-body">
                    <div class="empty"><div class="empty-icon">{ }</div><div>Awaiting Execution</div></div>
                  </div>
                </div>
              </div>

              <div class="panel">
                <div class="ph">
                  <span class="ph-title">Memory Registry</span>
                  <div style="display:flex;align-items:center;gap:1rem">
                    <span class="ktag" id="keys-count"></span>
                    <button class="copy-btn" onclick="loadKeys()">Sync</button>
                  </div>
                </div>
                <div class="keys-list" id="keys-list">
                  <div class="empty"><div class="empty-icon">0x0</div><div>Registry Empty</div></div>
                </div>
              </div>
            </main>

            <div class="toast" id="toast"></div>

            <script>
            'use strict';

            const themeBtn = document.getElementById('theme-btn');
            let currentTheme = localStorage.getItem('kv-theme') || 'dark';
            document.documentElement.setAttribute('data-theme', currentTheme);
            themeBtn.textContent = currentTheme === 'dark' ? 'Light' : 'Dark';

            function toggleTheme() {
              currentTheme = currentTheme === 'dark' ? 'light' : 'dark';
              document.documentElement.setAttribute('data-theme', currentTheme);
              localStorage.setItem('kv-theme', currentTheme);
              themeBtn.textContent = currentTheme === 'dark' ? 'Light' : 'Dark';
            }

            function togglePwd() {
              const inp = document.getElementById('auth-input');
              const btn = document.getElementById('pwd-toggle');
              if (inp.type === 'password') {
                inp.type = 'text';
                btn.textContent = 'HIDE';
              } else {
                inp.type = 'password';
                btn.textContent = 'SHOW';
              }
            }

            let authToken = '';

            function showAuthModal() {
              document.getElementById('auth-overlay').classList.remove('hidden');
              setTimeout(() => document.getElementById('auth-input').focus(), 50);
            }
            function hideAuthModal() { document.getElementById('auth-overlay').classList.add('hidden'); }

            function submitAuth() {
              const pw = document.getElementById('auth-input').value;
              if (!pw) { toast('Password cannot be empty', true); return; }
              authToken = pw;
              document.getElementById('auth-input').value = '';
              document.getElementById('hdr-auth').style.display = '';
              hideAuthModal();
              loadStats(); loadKeys();
            }

            async function apiFetch(url, opts) {
              const defaults = { headers: {} };
              if (authToken) defaults.headers['Authorization'] = 'Bearer ' + authToken;
              const merged = Object.assign({}, defaults, opts);
              if (opts && opts.headers) merged.headers = Object.assign({}, defaults.headers, opts.headers);
              const r = await fetch(url, merged);
              if (r.status === 401) { showAuthModal(); throw new Error('Unauthorized'); }
              return r;
            }

            function toast(msg, isErr) {
              const el = document.getElementById('toast');
              el.textContent = msg;
              el.className = 'toast show ' + (isErr ? 't-err' : 't-ok');
              clearTimeout(el._t);
              el._t = setTimeout(() => { el.className = 'toast ' + (isErr ? 't-err' : 't-ok'); }, 2600);
            }

            function busy(id, on) {
              const b = document.getElementById(id);
              if (!b) return;
              if (on) { b.disabled = true; b._h = b.innerHTML; b.innerHTML = '<div class="spin"></div>'; }
              else    { b.disabled = false; if (b._h) b.innerHTML = b._h; }
            }

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
                } else if (s.startsWith('true', i))  { out += '<span class="jb">true</span>';  i += 4; }
                  else if (s.startsWith('false', i)) { out += '<span class="jb">false</span>'; i += 5; }
                  else if (s.startsWith('null', i))  { out += '<span class="jl">null</span>';  i += 4; }
                  else { out += escHtml(ch); i++; }
              }
              return out;
            }

            let lastResp = '';
            function showResp(data, ok) {
              lastResp = JSON.stringify(data, null, 2);
              document.getElementById('resp-body').innerHTML = '<div class="fade">' + hlJson(JSON.stringify(data)) + '</div>';
              document.getElementById('resp-chip').innerHTML = ok ? '<span class="chip chip-ok">200 OK</span>' : '<span class="chip chip-err">ERR</span>';
              const ri = document.getElementById('ri');
              ri.className = 'ri flash';
              setTimeout(() => { ri.className = 'ri'; }, 500);
            }

            function switchTab(name, el) {
              document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
              document.querySelectorAll('.tp').forEach(p => p.classList.remove('active'));
              el.classList.add('active');
              document.getElementById('tp-' + name).classList.add('active');
            }

            async function doSet() {
              const key = document.getElementById('set-key').value.trim();
              const val = document.getElementById('set-value').value;
              const ttl = document.getElementById('set-ttl').value.trim();
              if (!key || val === '') { toast('Key and Value required', true); return; }
              busy('btn-set', true);
              try {
                let body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(val);
                if (ttl) body += '&ttl=' + encodeURIComponent(ttl);
                const r = await apiFetch('/api/set', {method:'POST',
                  headers:{'Content-Type':'application/x-www-form-urlencoded'}, body});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Key set'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-set', false); }
            }

            async function doGet() {
              const key = document.getElementById('get-key').value.trim();
              if (!key) { toast('Key required', true); return; }
              busy('btn-get', true);
              try {
                const r = await apiFetch('/api/get?key=' + encodeURIComponent(key));
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (!d.ok) toast(d.error || 'Not found', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-get', false); }
            }

            async function doDelete() {
              const key = document.getElementById('del-key').value.trim();
              if (!key) { toast('Key required', true); return; }
              busy('btn-delete', true);
              try {
                const r = await apiFetch('/api/delete?key=' + encodeURIComponent(key), {method:'DELETE'});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Key deleted'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-delete', false); }
            }

            async function doTtl() {
              const key = document.getElementById('ttl-key').value.trim();
              if (!key) { toast('Key required', true); return; }
              busy('btn-ttl', true);
              try {
                const r = await apiFetch('/api/ttl?key=' + encodeURIComponent(key));
                const d = await r.json(); showResp(d, r.ok);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-ttl', false); }
            }

            async function doLpush() {
              const key = document.getElementById('lp-key').value.trim();
              const val = document.getElementById('lp-val').value;
              if (!key || val === '') { toast('Key and Element required', true); return; }
              busy('btn-lpush', true);
              try {
                const body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(val);
                const r = await apiFetch('/api/lpush', {method:'POST',
                  headers:{'Content-Type':'application/x-www-form-urlencoded'}, body});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('LPUSH OK (len=' + d.length + ')'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-lpush', false); }
            }

            async function doLrange() {
              const key   = document.getElementById('lr-key').value.trim();
              const start = document.getElementById('lr-start').value;
              const end   = document.getElementById('lr-end').value;
              if (!key) { toast('Key required', true); return; }
              busy('btn-lrange', true);
              try {
                const r = await apiFetch('/api/lrange?key=' + encodeURIComponent(key) +
                                        '&start=' + start + '&end=' + end);
                const d = await r.json(); showResp(d, r.ok);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-lrange', false); }
            }

            async function doSadd() {
              const key = document.getElementById('sa-key').value.trim();
              const val = document.getElementById('sa-val').value;
              if (!key || val === '') { toast('Key and Member required', true); return; }
              busy('btn-sadd', true);
              try {
                const body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(val);
                const r = await apiFetch('/api/sadd', {method:'POST',
                  headers:{'Content-Type':'application/x-www-form-urlencoded'}, body});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast(d.added ? 'Member added' : 'Already a member'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-sadd', false); }
            }

            async function doSmembers() {
              const key = document.getElementById('sm-key').value.trim();
              if (!key) { toast('Key required', true); return; }
              busy('btn-smembers', true);
              try {
                const r = await apiFetch('/api/smembers?key=' + encodeURIComponent(key));
                const d = await r.json(); showResp(d, r.ok);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-smembers', false); }
            }

            async function doCompact() {
              busy('btn-compact', true);
              try {
                const r = await apiFetch('/api/compact', {method:'POST'});
                const d = await r.json();
                showResp(d, r.ok && d.ok);
                if (d.ok) { toast('Compaction done (' + d.elapsed_ms + 'ms)'); loadStats(); loadKeys(); }
                else toast(d.error || 'Error', true);
              } catch(e) { if (e.message!=='Unauthorized') toast(e.message,true); }
              finally { busy('btn-compact', false); }
            }

            async function loadStats() {
              try {
                const d = await (await apiFetch('/api/stats')).json();
                document.getElementById('s-live').textContent  = d['Live keys']      ?? '—';
                document.getElementById('s-str').textContent   = d['String keys']    ?? '—';
                document.getElementById('s-lst').textContent   = d['List keys']      ?? '—';
                document.getElementById('s-set').textContent   = d['Set keys']       ?? '—';
                document.getElementById('s-wal').textContent   = d['WAL file size']  ?? '—';
                document.getElementById('s-sets').textContent  = d['SET  ops']       ?? '—';
                document.getElementById('s-gets').textContent  = d['GET  ops']       ?? '—';
                document.getElementById('s-exp').textContent   = d['Expired purged'] ?? '—';
              } catch(_) {}
            }

            async function loadKeys() {
              try {
                const d = await (await apiFetch('/api/keys')).json();
                const keys = d.keys || [];
                document.getElementById('keys-count').textContent = keys.length + ' VOL';
                const list = document.getElementById('keys-list');
                if (!keys.length) {
                  list.innerHTML = '<div class="empty"><div class="empty-icon">0x0</div><div>Registry Empty</div></div>';
                  return;
                }
                list.innerHTML = keys.map(k => {
                  const ttlTxt  = (k.ttl >= 0) ? '<span class="kt">TTL ' + k.ttl + 's</span>' : '';
                  const typeCls = 'ktype-' + k.type;
                  return '<div class="ki" data-key="' + escHtml(k.key) + '" data-type="' + k.type + '">'
                    + '<div style="display:flex;align-items:center;gap:0.5rem">'
                    + '<span class="kn">' + escHtml(k.key) + '</span>'
                    + '<span class="ktype ' + typeCls + '">' + k.type + '</span>'
                    + '</div><div>' + ttlTxt + '</div></div>';
                }).join('');
                list.onclick = ev => {
                  const item = ev.target.closest('.ki');
                  if (!item) return;
                  const key = item.dataset.key;
                  const type = item.dataset.type;
                  if (type === 'list') {
                    switchTab('lrange', document.getElementById('tb-lrange'));
                    document.getElementById('lr-key').value = key;
                  } else if (type === 'set') {
                    switchTab('smembers', document.getElementById('tb-smembers'));
                    document.getElementById('sm-key').value = key;
                  } else {
                    switchTab('get', document.getElementById('tb-get'));
                    document.getElementById('get-key').value = key;
                  }
                };
              } catch(_) {}
            }

            function copyResp() {
              if (!lastResp) return;
              navigator.clipboard.writeText(lastResp).then(() => toast('COPIED TO CLIPBOARD'));
            }

            (async () => {
              try {
                const r = await fetch('/api/stats');
                if (r.status === 401) { showAuthModal(); return; }
                loadStats(); loadKeys();
              } catch(_) { loadStats(); loadKeys(); }
            })();
            setInterval(() => { loadStats(); loadKeys(); }, 3000);
            </script>
            </body>
            </html>
            """;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code WebServer} bound to {@code port}.
     *
     * @param engine      the storage engine (must already be open)
     * @param port        TCP port (1–65535)
     * @param requirepass required password, or {@code null} for no authentication
     */
    public WebServer(StorageEngine engine, int port, String requirepass) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        this.engine      = Objects.requireNonNull(engine, "engine must not be null");
        this.port        = port;
        this.requirepass = (requirepass != null && requirepass.isBlank()) ? null : requirepass;
    }

    /** Creates a {@code WebServer} on {@code port} with no authentication. */
    public WebServer(StorageEngine engine, int port) {
        this(engine, port, null);
    }

    /** Creates a {@code WebServer} on the default port 8081. */
    public WebServer(StorageEngine engine) {
        this(engine, 8081, null);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds the HTTP server socket and begins accepting requests on virtual threads.
     * Returns as soon as the socket is bound.
     *
     * @throws IOException if the socket cannot be bound
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        httpServer.createContext("/api/get",      ex -> safeHandle(ex, this::handleGet));
        httpServer.createContext("/api/set",      ex -> safeHandle(ex, this::handleSet));
        httpServer.createContext("/api/delete",   ex -> safeHandle(ex, this::handleDelete));
        httpServer.createContext("/api/ttl",      ex -> safeHandle(ex, this::handleTtl));
        httpServer.createContext("/api/type",     ex -> safeHandle(ex, this::handleType));
        httpServer.createContext("/api/stats",    ex -> safeHandle(ex, this::handleStats));
        httpServer.createContext("/api/keys",     ex -> safeHandle(ex, this::handleKeys));
        httpServer.createContext("/api/compact",  ex -> safeHandle(ex, this::handleCompact));
        httpServer.createContext("/api/lpush",    ex -> safeHandle(ex, this::handleLpush));
        httpServer.createContext("/api/lrange",   ex -> safeHandle(ex, this::handleLrange));
        httpServer.createContext("/api/sadd",     ex -> safeHandle(ex, this::handleSadd));
        httpServer.createContext("/api/smembers", ex -> safeHandle(ex, this::handleSmembers));
        httpServer.createContext("/",             ex -> safeHandle(ex, this::handleRoot));

        httpServer.start();
        LOG.info(String.format(
            "[WebServer] HTTP server on port %d — auth=%s (demo-grade: com.sun.net.httpserver)",
            port, requirepass != null ? "enabled" : "disabled"));
    }

    /** Stops the HTTP server, allowing up to 1 s for in-flight requests to finish. */
    public void shutdown() {
        HttpServer s = httpServer;
        if (s != null) { s.stop(1); httpServer = null; LOG.info("[WebServer] Shutdown."); }
    }

    public boolean isRunning() { return httpServer != null; }
    public int     getPort()   { return port; }

    // -------------------------------------------------------------------------
    // Internal routing scaffold
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface IOHandler { void handle(HttpExchange ex) throws IOException; }

    /**
     * Wraps a handler with CORS, OPTIONS preflight, auth check, and error recovery.
     */
    private void safeHandle(HttpExchange ex, IOHandler h) {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1); return;
            }

            // Auth gate — only applies to /api/ endpoints (not the SPA root).
            if (requirepass != null && ex.getRequestURI().getPath().startsWith("/api/")) {
                String authHeader = ex.getRequestHeaders().getFirst("Authorization");
                String expected   = "Bearer " + requirepass;
                if (authHeader == null || !authHeader.equals(expected)) {
                    sendJson(ex, 401, "{\"ok\":false,\"error\":\"Unauthorized — provide Authorization: Bearer <password>\"}");
                    return;
                }
            }

            h.handle(ex);
        } catch (IllegalStateException e) {
            // WRONGTYPE or similar
            try { sendError(ex, 400, e.getMessage()); } catch (IOException ignored) {}
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[WebServer] Unhandled error", t);
            try { sendError(ex, 500, "Internal server error: " + t.getMessage()); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Route handlers — core
    // -------------------------------------------------------------------------

    private void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            sendJson(ex, 404, "{\"ok\":false,\"error\":\"Unknown endpoint: " + jsonEscape(path) + "\"}");
        } else {
            sendHtml(ex, INDEX_HTML);
        }
    }

    /** GET /api/get?key=X */
    private void handleGet(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q   = parseQuery(ex.getRequestURI().getQuery());
        String              key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key' query parameter\"}"); return;
        }
        String valueType = engine.type(key);
        if ("list".equals(valueType)) {
            List<String> elems = engine.lrange(key, 0, -1);
            sendJson(ex, 200, buildListJson(key, elems)); return;
        }
        if ("set".equals(valueType)) {
            Set<String> members = engine.smembers(key);
            sendJson(ex, 200, buildSetJson(key, members)); return;
        }
        Optional<String> val = engine.get(key);
        if (val.isPresent()) {
            sendJson(ex, 200,
                "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"value\":\"" +
                jsonEscape(val.get()) + "\",\"type\":\"string\"}");
        } else {
            sendJson(ex, 404, "{\"ok\":false,\"key\":\"" + jsonEscape(key) + "\",\"error\":\"key not found\"}");
        }
    }

    /** POST /api/set — body: key=X&value=V[&ttl=N] */
    private void handleSet(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Map<String, String> body   = parseFormBody(ex);
        String              key    = body.get("key");
        String              value  = body.get("value");
        String              ttlStr = body.get("ttl");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        if (value == null) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'value'\"}"); return;
        }
        try {
            if (ttlStr != null && !ttlStr.isBlank()) {
                long ttl = Long.parseLong(ttlStr.trim());
                engine.set(key, value, ttl);
                sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"ttl\":" + ttl + "}");
            } else {
                engine.set(key, value);
                sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\"}");
            }
        } catch (NumberFormatException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"invalid TTL — must be a positive integer\"}");
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /** DELETE /api/delete?key=X */
    private void handleDelete(HttpExchange ex) throws IOException {
        requireMethod(ex, "DELETE");
        Map<String, String> q   = parseQuery(ex.getRequestURI().getQuery());
        String              key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        engine.delete(key);
        sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\"}");
    }

    /** GET /api/ttl?key=X */
    private void handleTtl(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q   = parseQuery(ex.getRequestURI().getQuery());
        String              key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        long rem = engine.ttl(key);
        sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"ttl\":" + rem + "}");
    }

    /** GET /api/type?key=X */
    private void handleType(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q   = parseQuery(ex.getRequestURI().getQuery());
        String              key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        String t = engine.type(key);
        sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) + "\",\"type\":\"" + t + "\"}");
    }

    /** GET /api/stats */
    private void handleStats(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        sendJson(ex, 200, buildStatsJson());
    }

    /** GET /api/keys */
    @SuppressWarnings("unchecked")
    private void handleKeys(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, Object> snap       = engine.indexSnapshot();
        Map<String, Long>   expirySnap = engine.expirySnapshot();
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder("{\"ok\":true,\"keys\":[");
        boolean first = true;
        for (String key : new TreeSet<>(snap.keySet())) {
            if (!first) sb.append(",");
            first = false;
            Object v = snap.get(key);
            String typeStr = (v instanceof String) ? "string"
                           : (v instanceof List)   ? "list"
                           : (v instanceof Set)    ? "set"
                           : "unknown";
            Long expMs = expirySnap.get(key);
            long ttlSec = (expMs == null) ? -1L : Math.max(-2L, (expMs - now) / 1000L);
            sb.append("{\"key\":\"").append(jsonEscape(key))
              .append("\",\"type\":\"").append(typeStr)
              .append("\",\"ttl\":").append(ttlSec).append("}");
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    /** POST /api/compact */
    private void handleCompact(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        long t0 = System.currentTimeMillis();
        engine.compact();
        long elapsed = System.currentTimeMillis() - t0;
        sendJson(ex, 200, "{\"ok\":true,\"elapsed_ms\":" + elapsed +
            ",\"live_keys\":" + engine.liveKeyCount() + "}");
    }

    // -------------------------------------------------------------------------
    // Route handlers — lists
    // -------------------------------------------------------------------------

    /** POST /api/lpush — body: key=X&value=V */
    private void handleLpush(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Map<String, String> body  = parseFormBody(ex);
        String              key   = body.get("key");
        String              value = body.get("value");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        if (value == null) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'value'\"}"); return;
        }
        int len = engine.lpush(key, value);
        sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) +
            "\",\"length\":" + len + "}");
    }

    /** GET /api/lrange?key=X[&start=0&end=-1] */
    private void handleLrange(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q     = parseQuery(ex.getRequestURI().getQuery());
        String              key   = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        int start = 0, end = -1;
        try {
            if (q.containsKey("start")) start = Integer.parseInt(q.get("start"));
            if (q.containsKey("end"))   end   = Integer.parseInt(q.get("end"));
        } catch (NumberFormatException e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"start and end must be integers\"}"); return;
        }
        List<String> elems = engine.lrange(key, start, end);
        sendJson(ex, 200, buildListJson(key, elems));
    }

    // -------------------------------------------------------------------------
    // Route handlers — sets
    // -------------------------------------------------------------------------

    /** POST /api/sadd — body: key=X&value=V */
    private void handleSadd(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Map<String, String> body   = parseFormBody(ex);
        String              key    = body.get("key");
        String              value  = body.get("value");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        if (value == null) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'value'\"}"); return;
        }
        boolean added = engine.sadd(key, value);
        sendJson(ex, 200, "{\"ok\":true,\"key\":\"" + jsonEscape(key) +
            "\",\"added\":" + added + "}");
    }

    /** GET /api/smembers?key=X */
    private void handleSmembers(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        Map<String, String> q   = parseQuery(ex.getRequestURI().getQuery());
        String              key = q.get("key");
        if (key == null || key.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing 'key'\"}"); return;
        }
        Set<String> members = engine.smembers(key);
        sendJson(ex, 200, buildSetJson(key, members));
    }

    // -------------------------------------------------------------------------
    // JSON builders
    // -------------------------------------------------------------------------

    private static String buildListJson(String key, List<String> elems) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"key\":\"")
            .append(jsonEscape(key)).append("\",\"type\":\"list\",\"elements\":[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(elems.get(i))).append("\"");
        }
        return sb.append("]}").toString();
    }

    private static String buildSetJson(String key, Set<String> members) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"key\":\"")
            .append(jsonEscape(key)).append("\",\"type\":\"set\",\"members\":[");
        boolean first = true;
        for (String m : new TreeSet<>(members)) {   // sorted for determinism
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(m)).append("\"");
        }
        return sb.append("]}").toString();
    }

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
        return sb.append("}").toString();
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private static void requireMethod(HttpExchange ex, String method) {
        if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
            throw new IllegalStateException("Method " + ex.getRequestMethod() +
                " not allowed — use " + method);
        }
    }

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

    private static Map<String, String> parseFormBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        return parseQuery(new String(raw, StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", MIME_JSON);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", MIME_HTML);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        sendJson(ex, code, "{\"ok\":false,\"error\":\"" + jsonEscape(msg) + "\"}");
    }

    /**
     * Escapes a string for safe inclusion inside a JSON string value.
     * Handles {@code "}, {@code \}, CR, LF, TAB, and all other control chars (U+0000–U+001F).
     *
     * @param s raw string (may be null)
     * @return JSON-safe escaped string, without surrounding quotes
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
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                               else sb.append(c); }
            }
        }
        return sb.toString();
    }
}
