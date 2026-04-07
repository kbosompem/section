(ns section.perch
  "The Perch — Operations' elevated view. HTTP dashboard for Birkoff.
   Read-only observer of Madeline's files + live action buttons."
  (:require [org.httpkit.server :as http]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [section.config :as config]
            [section.walter :as walter]
            [section.madeline :as madeline]
            [section.registry :as registry]
            [section.comm :as comm]))

;; ---------------------------------------------------------------------------
;; Data gathering
;; ---------------------------------------------------------------------------

(defn heartbeat-file []
  (str (config/workdir) "/heartbeat.edn"))

(defn read-heartbeat []
  (when (fs/exists? (heartbeat-file))
    (try
      (edn/read-string (slurp (heartbeat-file)))
      (catch Exception _ nil))))

(defn- parse-instant [s]
  (when s (try (java.time.Instant/parse s) (catch Exception _ nil))))

(defn- now [] (java.time.Instant/now))

(defn time-ago
  "Human-readable time delta from an ISO instant."
  [iso-string]
  (if-let [t (parse-instant iso-string)]
    (let [secs (quot (- (.toEpochMilli (now)) (.toEpochMilli t)) 1000)]
      (cond
        (< secs 0)     "just now"
        (< secs 60)    (str secs "s ago")
        (< secs 3600)  (str (quot secs 60) "m ago")
        (< secs 86400) (str (quot secs 3600) "h ago")
        :else          (str (quot secs 86400) "d ago")))
    "—"))

(defn time-until
  "Human-readable delta to a future ISO instant."
  [iso-string]
  (if-let [t (parse-instant iso-string)]
    (let [secs (quot (- (.toEpochMilli t) (.toEpochMilli (now))) 1000)]
      (cond
        (neg? secs)    "overdue"
        (< secs 60)    (str "in " secs "s")
        (< secs 3600)  (str "in " (quot secs 60) "m")
        :else          (str "in " (quot secs 3600) "h")))
    "—"))

(defn recent?
  "Is this ISO instant within the last N hours?"
  [iso-string hours]
  (when-let [t (parse-instant iso-string)]
    (< (- (.toEpochMilli (now)) (.toEpochMilli t))
       (* hours 60 60 1000))))

(defn mission-count-by-status
  "Return {:in-progress N :completed-today N :failed-today N}."
  [missions]
  {:in-progress (count (filter #(= :in-progress (:status (val %))) missions))
   :completed-today (count (filter #(and (= :completed (:status (val %)))
                                          (recent? (:completed-at (val %)) 24))
                                    missions))
   :failed-today (count (filter #(and (#{:failed :error} (:status (val %)))
                                       (recent? (:updated-at (val %)) 24))
                                 missions))})

(defn recent-missions
  "Return the N most-recently updated missions."
  [missions n]
  (->> missions
       (sort-by (comp #(or % "0") :updated-at val) #(compare %2 %1))
       (take n)
       (map (fn [[k v]]
              (-> v
                  (assoc :id (name k))
                  (assoc :ago (time-ago (:updated-at v))))))))

;; ---------------------------------------------------------------------------
;; HTML helpers
;; ---------------------------------------------------------------------------

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; ---------------------------------------------------------------------------
;; Page shell
;; ---------------------------------------------------------------------------

(def page-css
  "
  :root {
    --bg: #0a0a0a;
    --bg-panel: #121212;
    --bg-hover: #1a1a1a;
    --border: #2a2a2a;
    --amber: #ffb000;
    --amber-dim: #b37c00;
    --green: #00ff88;
    --red: #ff4444;
    --text: #e0e0e0;
    --text-dim: #808080;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: 'JetBrains Mono', 'SF Mono', Monaco, 'Courier New', monospace;
    font-size: 13px;
    line-height: 1.5;
    min-height: 100vh;
    padding: 16px;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    border: 1px solid var(--amber-dim);
    background: var(--bg-panel);
    margin-bottom: 16px;
    letter-spacing: 2px;
  }
  header .title {
    color: var(--amber);
    font-size: 16px;
    font-weight: bold;
  }
  header .meta {
    color: var(--text-dim);
    font-size: 11px;
    text-align: right;
    line-height: 1.4;
  }
  header .meta b { color: var(--green); }
  header .meta b.ok  { color: var(--green); }
  header .meta b.err { color: var(--red); }
  main {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }
  .panel {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    padding: 12px 16px;
  }
  .panel.full { grid-column: 1 / -1; }
  .panel h2 {
    color: var(--amber);
    font-size: 11px;
    letter-spacing: 2px;
    margin-bottom: 10px;
    border-bottom: 1px solid var(--border);
    padding-bottom: 6px;
  }
  .panel .content { min-height: 60px; }
  .kv { display: flex; justify-content: space-between; padding: 2px 0; }
  .kv .k { color: var(--text-dim); }
  .kv .v { color: var(--text); }
  .kv .v.ok   { color: var(--green); }
  .kv .v.warn { color: var(--amber); }
  .kv .v.err  { color: var(--red); }
  .cap { display: flex; align-items: center; padding: 2px 0; }
  .cap .mark { width: 16px; font-weight: bold; }
  .cap .mark.ok { color: var(--green); }
  .cap .mark.no { color: var(--red); }
  .cap .name { flex: 1; }
  .cap .desc { color: var(--text-dim); font-size: 11px; }
  .mission-row {
    padding: 4px 6px;
    border-bottom: 1px dashed var(--border);
    display: flex;
    justify-content: space-between;
    gap: 8px;
    text-decoration: none;
    color: inherit;
  }
  .mission-row:last-child { border-bottom: none; }
  .mission-row:hover { background: var(--bg-hover); text-decoration: none; }
  .mission-row .id { color: var(--amber); }
  .mission-row .title { color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mission-row .status { color: var(--text-dim); font-size: 11px; }
  .mission-row .status.ok  { color: var(--green); }
  .mission-row .status.err { color: var(--red); }
  .back {
    color: var(--amber-dim);
    font-size: 11px;
    letter-spacing: 1px;
    text-transform: uppercase;
    padding: 4px 8px;
    border: 1px solid var(--border);
  }
  .back:hover { color: var(--amber); border-color: var(--amber-dim); text-decoration: none; }
  pre.log {
    background: #050505;
    border: 1px solid var(--border);
    color: var(--text);
    padding: 12px;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 12px;
    line-height: 1.45;
    max-height: 70vh;
  }
  .detail-grid .kv { padding: 4px 0; border-bottom: 1px dashed var(--border); }
  .detail-grid .kv:last-child { border-bottom: none; }
  .detail-grid .v { text-align: right; max-width: 70%; word-break: break-word; }
  .empty { color: var(--text-dim); font-style: italic; padding: 8px 0; }
  footer {
    display: flex;
    gap: 8px;
    margin-top: 16px;
    padding: 12px;
    border: 1px solid var(--border);
    background: var(--bg-panel);
  }
  button {
    background: var(--bg);
    color: var(--amber);
    border: 1px solid var(--amber-dim);
    padding: 8px 16px;
    font-family: inherit;
    font-size: 11px;
    letter-spacing: 1px;
    cursor: pointer;
    text-transform: uppercase;
  }
  button:hover {
    background: var(--amber);
    color: var(--bg);
  }
  #cy { width: 100%; height: 420px; background: #080808; border: 1px solid var(--border); }
  .flash {
    position: fixed;
    top: 16px;
    right: 16px;
    background: var(--bg-panel);
    border: 1px solid var(--amber);
    color: var(--amber);
    padding: 8px 16px;
    font-size: 12px;
  }
  a { color: var(--amber); text-decoration: none; }
  a:hover { text-decoration: underline; }
  .avatar {
    width: 48px;
    height: 48px;
    flex-shrink: 0;
  }
  header { gap: 12px; }
  header .title-group {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  @media (max-width: 900px) {
    main { grid-template-columns: 1fr; }
  }
  ")

(defn page
  "Render the full dashboard page."
  []
  (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>Section — The Perch</title>
<link href=\"https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700&display=swap\" rel=\"stylesheet\">
<script src=\"https://unpkg.com/htmx.org@1.9.12\"></script>
<script src=\"https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js\"></script>
<style>" page-css "</style>
</head>
<body>
<header>
  <div class=\"title-group\">
    <svg class=\"avatar\" viewBox=\"0 0 48 48\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\" aria-label=\"Birkoff\">
      <rect width=\"48\" height=\"48\" rx=\"4\" fill=\"#0a0a0a\" stroke=\"#ffb000\" stroke-width=\"1\"/>
      <!-- Circuit traces -->
      <line x1=\"4\" y1=\"12\" x2=\"12\" y2=\"12\" stroke=\"#b37c00\" stroke-width=\"0.5\"/>
      <line x1=\"36\" y1=\"12\" x2=\"44\" y2=\"12\" stroke=\"#b37c00\" stroke-width=\"0.5\"/>
      <line x1=\"4\" y1=\"36\" x2=\"12\" y2=\"36\" stroke=\"#b37c00\" stroke-width=\"0.5\"/>
      <line x1=\"36\" y1=\"36\" x2=\"44\" y2=\"36\" stroke=\"#b37c00\" stroke-width=\"0.5\"/>
      <!-- Head -->
      <rect x=\"14\" y=\"8\" width=\"20\" height=\"22\" rx=\"3\" fill=\"#1a1a1a\" stroke=\"#ffb000\" stroke-width=\"1\"/>
      <!-- Eyes -->
      <rect x=\"17\" y=\"14\" width=\"5\" height=\"3\" rx=\"1\" fill=\"#ffb000\"/>
      <rect x=\"26\" y=\"14\" width=\"5\" height=\"3\" rx=\"1\" fill=\"#ffb000\"/>
      <!-- Glasses bridge -->
      <line x1=\"22\" y1=\"15.5\" x2=\"26\" y2=\"15.5\" stroke=\"#b37c00\" stroke-width=\"0.8\"/>
      <!-- Mouth / terminal cursor -->
      <rect x=\"18\" y=\"22\" width=\"8\" height=\"1.5\" rx=\"0.5\" fill=\"#808080\"/>
      <rect x=\"27\" y=\"22\" width=\"2\" height=\"1.5\" rx=\"0.5\" fill=\"#00ff88\"/>
      <!-- Shoulders -->
      <rect x=\"10\" y=\"30\" width=\"28\" height=\"10\" rx=\"2\" fill=\"#1a1a1a\" stroke=\"#ffb000\" stroke-width=\"1\"/>
      <!-- Collar detail -->
      <line x1=\"21\" y1=\"30\" x2=\"24\" y2=\"35\" stroke=\"#b37c00\" stroke-width=\"0.8\"/>
      <line x1=\"27\" y1=\"30\" x2=\"24\" y2=\"35\" stroke=\"#b37c00\" stroke-width=\"0.8\"/>
    </svg>
    <div class=\"title\">SECTION — THE PERCH</div>
  </div>
  <div class=\"meta\" hx-get=\"/api/header\" hx-trigger=\"load, every 10s\" hx-swap=\"innerHTML\">...</div>
</header>

<main>
  <section class=\"panel\">
    <h2>WALTER — CAPABILITIES</h2>
    <div class=\"content\" hx-get=\"/api/walter\" hx-trigger=\"load, every 30s\" hx-swap=\"innerHTML\">...</div>
  </section>

  <section class=\"panel\">
    <h2>OPERATIONS — COUNTS</h2>
    <div class=\"content\" hx-get=\"/api/operations\" hx-trigger=\"load, every 10s\" hx-swap=\"innerHTML\">...</div>
  </section>

  <section class=\"panel full\">
    <h2>MADELINE — REGISTRY</h2>
    <div id=\"cy\"></div>
  </section>

  <section class=\"panel\">
    <h2>ABEYANCE — ACTIVE &amp; IN FLIGHT</h2>
    <div class=\"content\" hx-get=\"/api/abeyance\" hx-trigger=\"load, every 10s\" hx-swap=\"innerHTML\">...</div>
  </section>

  <section class=\"panel\">
    <h2>RECENT EGRESS</h2>
    <div class=\"content\" hx-get=\"/api/egress\" hx-trigger=\"load, every 10s\" hx-swap=\"innerHTML\">...</div>
  </section>
</main>

<footer>
  <button hx-post=\"/actions/run\"          hx-swap=\"none\">RUN NOW</button>
  <button hx-post=\"/actions/housekeeping\" hx-swap=\"none\">HOUSEKEEPING</button>
</footer>

<script>
function loadGraph() {
  fetch('/api/graph').then(r => r.json()).then(data => {
    const cy = cytoscape({
      container: document.getElementById('cy'),
      elements: data,
      maxZoom: 1.1,
      minZoom: 0.3,
      wheelSensitivity: 0.2,
      style: [
        { selector: 'node', style: {
            'background-color': '#1a1a1a',
            'border-color': '#ffb000',
            'border-width': 1,
            'label': 'data(label)',
            'color': '#ffb000',
            'text-valign': 'center',
            'text-halign': 'center',
            'text-wrap': 'wrap',
            'text-max-width': '140px',
            'font-family': 'JetBrains Mono, monospace',
            'font-size': 11,
            'font-weight': 'normal',
            'width': 'label',
            'height': 'label',
            'padding': '8px',
            'shape': 'round-rectangle'
        }},
        { selector: 'edge', style: {
            'width': 1,
            'line-color': '#b37c00',
            'target-arrow-color': '#b37c00',
            'target-arrow-shape': 'triangle',
            'arrow-scale': 0.8,
            'curve-style': 'bezier',
            'label': 'data(label)',
            'color': '#b37c00',
            'font-family': 'JetBrains Mono, monospace',
            'font-size': 9,
            'text-background-color': '#080808',
            'text-background-opacity': 1,
            'text-background-padding': 3
        }}
      ],
      layout: (data.length < 6)
        ? { name: 'circle', padding: 60, radius: 120, animate: false, fit: true }
        : { name: 'cose', animate: false, padding: 40,
            nodeRepulsion: 40000, idealEdgeLength: 160, fit: true,
            componentSpacing: 80 }
    });
    // Cap the zoom after layout runs
    cy.ready(() => { cy.fit(undefined, 50); if (cy.zoom() > 1.1) cy.zoom(1.1); cy.center(); });
  });
}
loadGraph();
setInterval(loadGraph, 60000);

// Toast flash for actions
document.body.addEventListener('htmx:afterRequest', (e) => {
  if (e.detail.requestConfig.verb === 'post') {
    const f = document.createElement('div');
    f.className = 'flash';
    f.textContent = e.detail.successful ? 'DISPATCHED' : 'FAILED';
    document.body.appendChild(f);
    setTimeout(() => f.remove(), 2000);
  }
});
</script>
</body>
</html>"))

;; ---------------------------------------------------------------------------
;; Mission detail — full-page drilldown
;; ---------------------------------------------------------------------------

(defn read-mission-log
  "Return the contents of a mission's log file, or nil if absent.
   The log id matches the mission key (e.g. 'kbosompem_section_2')."
  [id]
  (let [f (str (config/logs-dir) "/" id ".log")]
    (when (fs/exists? f)
      (try (slurp f) (catch Exception _ nil)))))

(defn get-mission-by-id
  "Look up a mission by its string id (the keyword name) without re-keywording
   user input loosely. Returns nil for unknown ids."
  [id]
  (when (and id (re-matches #"[A-Za-z0-9_\-]+" id))
    (get (:missions (madeline/load-memory)) (keyword id))))

(defn- detail-row
  "Render one key/value row in the mission detail panel. Skips empty values."
  [k v]
  (when (and v (seq (str v)))
    (str "<div class=\"kv\"><span class=\"k\">" (esc k) "</span>"
         "<span class=\"v\">" (esc v) "</span></div>")))

(defn- pr-link-row
  "Render the PR row as an actual clickable link, when present."
  [url]
  (when (and url (seq (str url)))
    (str "<div class=\"kv\"><span class=\"k\">Pull request</span>"
         "<span class=\"v\"><a href=\"" (esc url) "\" target=\"_blank\" rel=\"noopener\">"
         (esc url) "</a></span></div>")))

(defn render-mission-detail
  "Full HTML page for one mission. Renders the madeline record plus the
   per-mission log file. If the id is unknown, renders a 'not found' page —
   the handler is responsible for the 404 status code."
  [id]
  (let [mission (get-mission-by-id id)
        body    (if (nil? mission)
                  (str "<main><section class=\"panel full\">"
                       "<h2>NOT FOUND</h2>"
                       "<div class=\"empty\">No mission with id <code>"
                       (esc id) "</code> in Madeline's memory.</div>"
                       "</section></main>")
                  (let [log    (or (read-mission-log id) "(no log file recorded)")
                        status (some-> (:status mission) name)]
                    (str
                      "<main>
  <section class=\"panel full\">
    <h2>RECORD</h2>
    <div class=\"content detail-grid\">"
                      (detail-row "Title"     (:title mission))
                      (detail-row "Status"    status)
                      (detail-row "Branch"    (:branch mission))
                      (detail-row "Attempts"  (:attempts mission))
                      (detail-row "Reason"    (:reason mission))
                      (detail-row "Summary"   (:summary mission))
                      (detail-row "Note"      (:note mission))
                      (pr-link-row (:pr-url mission))
                      (detail-row "Updated"   (:updated-at mission))
                      (detail-row "Completed" (:completed-at mission))
                      "</div>
  </section>
  <section class=\"panel full\">
    <h2>LOG</h2>
    <pre class=\"log\">" (esc log) "</pre>
  </section>
</main>")))]
    (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>Mission " (esc id) " — The Perch</title>
<link href=\"https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700&display=swap\" rel=\"stylesheet\">
<style>" page-css "</style>
</head>
<body>
<header>
  <div class=\"title-group\">
    <a href=\"/\" class=\"back\">← Back</a>
    <div class=\"title\">MISSION " (esc id) "</div>
  </div>
  <div class=\"meta\"></div>
</header>
" body "
</body>
</html>")))

;; ---------------------------------------------------------------------------
;; Partial renderers (returned by /api endpoints for HTMX)
;; ---------------------------------------------------------------------------

(defn render-header []
  (let [hb (read-heartbeat)
        last-ts (:timestamp hb)
        phase (:phase hb)
        next-ts (when last-ts
                  (str (.plusSeconds (parse-instant last-ts) 300)))
        auth (comm/auth-status)
        auth-cls (if (:ok? auth) "ok" "err")
        auth-text (if (:ok? auth)
                    (str (:user auth) " ✓")
                    (str (or (:user auth) "—")
                         " ✗ (expect " (:expected auth) ")"))]
    (str "<div>Phase: <b>" (esc (or (some-> phase name) "unknown")) "</b></div>"
         "<div>Last cycle: " (esc (time-ago last-ts)) "</div>"
         "<div>Next cycle: " (esc (time-until next-ts)) "</div>"
         "<div>gh: <b class=\"" auth-cls "\">" (esc auth-text) "</b></div>"
         "<div style=\"color:#444;font-size:10px;\">" (esc (str (now))) "</div>")))

(defn render-walter []
  (let [caps (walter/check-all)]
    (str/join ""
      (for [[k v] caps]
        (str "<div class=\"cap\">"
             "<span class=\"mark " (if (:available? v) "ok" "no") "\">"
             (if (:available? v) "✓" "✗") "</span>"
             "<span class=\"name\">" (esc (name k)) "</span>"
             "<span class=\"desc\">" (esc (:description v)) "</span>"
             "</div>")))))

(defn render-operations []
  (let [missions (:missions (madeline/load-memory))
        c (mission-count-by-status missions)
        caps (walter/check-all)
        repos (registry/list-repos)
        n-ok (count (filter #(:available? (val %)) caps))
        n-total (count caps)]
    (str
      "<div class=\"kv\"><span class=\"k\">In flight</span><span class=\"v " (if (pos? (:in-progress c)) "warn" "") "\">" (:in-progress c) "</span></div>"
      "<div class=\"kv\"><span class=\"k\">Completed 24h</span><span class=\"v ok\">" (:completed-today c) "</span></div>"
      "<div class=\"kv\"><span class=\"k\">Failed 24h</span><span class=\"v " (if (pos? (:failed-today c)) "err" "") "\">" (:failed-today c) "</span></div>"
      "<div class=\"kv\"><span class=\"k\">Repos monitored</span><span class=\"v\">" (count repos) "</span></div>"
      "<div class=\"kv\"><span class=\"k\">Capabilities</span><span class=\"v\">" n-ok "/" n-total "</span></div>")))

(defn render-abeyance []
  (let [missions (:missions (madeline/load-memory))
        active (filter #(#{:in-progress} (:status (val %))) missions)]
    (if (empty? active)
      "<div class=\"empty\">Section stands ready. No active missions.</div>"
      (str/join ""
        (for [[k v] active
              :let [id (name k)]]
          (str "<a class=\"mission-row\" href=\"/mission/" (esc id) "\">"
               "<span class=\"id\">" (esc id) "</span>"
               "<span class=\"title\">" (esc (or (:title v) "")) "</span>"
               "<span class=\"status warn\">in flight " (esc (time-ago (:updated-at v))) "</span>"
               "</a>"))))))

(defn render-egress []
  (let [missions (:missions (madeline/load-memory))
        recent (recent-missions missions 10)]
    (if (empty? recent)
      "<div class=\"empty\">No missions on record.</div>"
      (str/join ""
        (for [m recent]
          (let [status (:status m)
                css-class (case status
                            :completed "ok"
                            (:failed :error) "err"
                            "")
                mark (case status
                       :completed "✓"
                       (:failed :error) "✗"
                       "·")]
            (str "<a class=\"mission-row\" href=\"/mission/" (esc (:id m)) "\">"
                 "<span class=\"id\">" mark " " (esc (:id m)) "</span>"
                 "<span class=\"title\">" (esc (or (:title m) "—")) "</span>"
                 "<span class=\"status " css-class "\">"
                 (esc (or (some-> status name) "unknown")) " " (esc (:ago m))
                 "</span>"
                 "</a>")))))))

;; ---------------------------------------------------------------------------
;; Graph JSON for cytoscape
;; ---------------------------------------------------------------------------

(defn graph-data
  "Build cytoscape-compatible graph data from the registry."
  []
  (let [reg (registry/load-registry)
        nodes (for [[_ entry] reg]
                {:data {:id (:name entry)
                        :label (if (:role entry)
                                 (str (:name entry) " [" (:role entry) "]")
                                 (:name entry))}})
        edges (for [[_ entry] reg
                    rel (:relationships entry)
                    :when (get reg (registry/repo-key (:to rel)))]
                {:data {:id (str (:name entry) "->" (:to rel) "-" (name (:type rel)))
                        :source (:name entry)
                        :target (:to rel)
                        :label (name (:type rel))}})]
    (vec (concat nodes edges))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn trigger-run!
  "Kick off a Birkoff cycle in the background."
  []
  (future
    (try
      (p/sh ["bb" "run"] {:dir (:section-root config/config)
                           :err :string :out :string})
      (catch Exception e
        (println "Perch: trigger-run! failed:" (.getMessage e))))))

(defn trigger-housekeeping!
  "Run housekeeping in the background."
  []
  (future
    (try
      (p/sh ["bb" "housekeeping"] {:dir (:section-root config/config)
                                    :err :string :out :string})
      (catch Exception e
        (println "Perch: trigger-housekeeping! failed:" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Router
;; ---------------------------------------------------------------------------

(defn- ok [body & [content-type]]
  {:status 200
   :headers {"content-type" (or content-type "text/html; charset=utf-8")
             "cache-control" "no-store"}
   :body body})

(defn handler [req]
  (let [m (:request-method req)
        u (:uri req)]
    (cond
      ;; Mission detail drilldown — id comes from the path.
      (and (= m :get) (str/starts-with? u "/mission/"))
      (let [id (subs u (count "/mission/"))]
        (if (get-mission-by-id id)
          (ok (render-mission-detail id))
          {:status 404
           :headers {"content-type" "text/html; charset=utf-8"
                     "cache-control" "no-store"}
           :body (render-mission-detail id)}))

      :else
      (case [m u]
        [:get  "/"]                     (ok (page))
        [:get  "/api/header"]           (ok (render-header))
        [:get  "/api/walter"]           (ok (render-walter))
        [:get  "/api/operations"]       (ok (render-operations))
        [:get  "/api/abeyance"]         (ok (render-abeyance))
        [:get  "/api/egress"]           (ok (render-egress))
        [:get  "/api/graph"]            (ok (json/write-str (graph-data)) "application/json")
        [:post "/actions/run"]          (do (trigger-run!)          (ok "ok"))
        [:post "/actions/housekeeping"] (do (trigger-housekeeping!) (ok "ok"))
        {:status 404 :body "Not found"}))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defonce server (atom nil))

(defn start!
  "Start the Perch web server on the given port (default 8080).
   Idempotent — stops an existing server first."
  [& {:keys [port] :or {port 8080}}]
  (when-let [stop @server]
    (stop :timeout 100)
    (reset! server nil))
  (let [stop-fn (http/run-server handler {:port port})]
    (reset! server stop-fn)
    (println (str "Perch: online at http://localhost:" port))
    stop-fn))

(defn stop! []
  (when-let [stop @server]
    (stop :timeout 100)
    (reset! server nil)
    (println "Perch: offline")))

(defn -main [& args]
  (let [port (or (some-> (first args) parse-long)
                 (some-> (System/getenv "PERCH_PORT") parse-long)
                 8080)]
    (start! :port port)
    ;; Block forever
    @(promise)))
