(ns section.sim-test
  "Sims — Section's test suite. Run before every egress."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [section.config :as config]
            [section.walter :as walter]
            [section.madeline :as madeline]
            [section.briefing :as briefing]
            [section.operations :as ops]
            [section.registry :as registry]
            [section.util :as util]
            [section.perch :as perch]))

;; ---------------------------------------------------------------------------
;; Minimal test framework
;; ---------------------------------------------------------------------------

(def failures (atom []))
(def passes (atom 0))

(defmacro deftest [tname & body]
  `(defn ~tname []
     (try
       ~@body
       (swap! passes inc)
       (println (str "  ✓ " ~(str tname)))
       (catch Exception e#
         (swap! failures conj {:test ~(str tname) :error (.getMessage e#)})
         (println (str "  ✗ " ~(str tname) " — " (.getMessage e#)))))))

(defmacro is [expr & [msg]]
  `(when-not ~expr
     (throw (Exception. (or ~msg (str "Assertion failed: " '~expr))))))

;; ---------------------------------------------------------------------------
;; Config tests
;; ---------------------------------------------------------------------------

(deftest test-config-loads
  (is (map? config/config) "config should be a map")
  (is (string? (:bot-user config/config)) "bot-user should be a string")
  (is (vector? (:repos config/config)) "repos should be a vector")
  (is (pos? (:max-turns config/config)) "max-turns should be positive")
  (is (pos? (:pool-size config/config)) "pool-size should be positive")
  (is (pos? (:timeout-ms config/config)) "timeout-ms should be positive"))

(deftest test-ensure-dirs
  (config/ensure-dirs!)
  (is (fs/exists? (config/workdir)) "workdir should exist")
  (is (fs/exists? (config/locks-dir)) "locks-dir should exist")
  (is (fs/exists? (config/logs-dir)) "logs-dir should exist")
  (is (fs/exists? (config/repos-dir)) "repos-dir should exist"))

;; ---------------------------------------------------------------------------
;; Walter tests
;; ---------------------------------------------------------------------------

(deftest test-capabilities-edn-valid
  (let [caps-file (str (:section-root config/config) "/walter/capabilities.edn")]
    (is (fs/exists? caps-file) "capabilities.edn should exist")
    (let [caps (edn/read-string (slurp caps-file))]
      (is (map? caps) "capabilities should be a map")
      (doseq [[k v] caps]
        (is (keyword? k) (str "capability key should be keyword: " k))
        (is (vector? (:check v)) (str "capability " (name k) " needs :check"))
        (is (string? (:install v)) (str "capability " (name k) " needs :install"))
        (is (string? (:description v)) (str "capability " (name k) " needs :description"))))))

(deftest test-walter-check-all
  (let [caps (walter/check-all)]
    (is (map? caps) "check-all should return a map")
    (doseq [[_ v] caps]
      (is (contains? v :available?) "each capability should have :available?"))))

(deftest test-walter-capability-manifest
  (let [manifest (walter/capability-manifest)]
    (is (string? manifest) "manifest should be a string")
    (is (str/includes? manifest "INSTALLED") "manifest should show installed status")))

;; ---------------------------------------------------------------------------
;; Madeline tests
;; ---------------------------------------------------------------------------

(deftest test-madeline-memory-roundtrip
  (let [original (madeline/load-memory)]
    ;; Save a test mission
    (madeline/save-mission! "test/repo" 999
      {:status :testing :title "Sim test"})
    ;; Read it back
    (let [m (madeline/get-mission "test/repo" 999)]
      (is (= :testing (:status m)) "mission status should roundtrip")
      (is (= "Sim test" (:title m)) "mission title should roundtrip"))
    ;; Complete it
    (madeline/complete-mission! "test/repo" 999
      {:summary "Test passed"})
    (let [m (madeline/get-mission "test/repo" 999)]
      (is (= :completed (:status m)) "mission should be completed"))
    ;; Clean up — restore original
    (madeline/save-memory! original)))

(deftest test-madeline-mission-context
  (let [ctx (madeline/mission-context "nonexistent/repo" 0)]
    (is (string? ctx) "mission-context should return a string")
    (is (str/includes? ctx "No prior work") "should indicate no prior work")))

;; ---------------------------------------------------------------------------
;; Briefing tests
;; ---------------------------------------------------------------------------

(deftest test-briefing-assembly
  (let [issue {"number" 42
               "title" "Test issue"
               "body" "This is a test"
               "comments" []}
        brief (briefing/assemble "test/repo" "/tmp" issue)]
    (is (map? brief) "briefing should be a map")
    (is (string? (:prompt brief)) "should have a prompt")
    (is (string? (:system-prompt brief)) "should have a system prompt")
    (is (str/includes? (:prompt brief) "#42") "prompt should reference issue number")
    (is (str/includes? (:system-prompt brief) "Section Operative")
        "system prompt should include operative briefing")))

;; ---------------------------------------------------------------------------
;; Operations tests
;; ---------------------------------------------------------------------------

(deftest test-lock-lifecycle
  (config/ensure-dirs!)
  (let [repo "test/locks" number 1]
    ;; Clean up any leftover from prior run
    (ops/unlock! repo number)
    (is (not (ops/locked? repo number)) "should not be locked initially")
    (ops/lock! repo number)
    (is (ops/locked? repo number) "should be locked after lock!")
    (ops/unlock! repo number)
    (is (not (ops/locked? repo number)) "should be unlocked after unlock!")))

;; ---------------------------------------------------------------------------
;; Registry tests
;; ---------------------------------------------------------------------------

(deftest test-registry-crud
  ;; Save current registry so we can restore it
  (let [original (registry/load-registry)]
    (try
      ;; Clean slate
      (registry/save-registry! {})

      ;; Add
      (registry/add! "test/alpha" {:description "Test repo A" :role "service"})
      (registry/add! "test/beta"  {:description "Test repo B" :role "frontend"})

      (is (= 2 (count (registry/list-repos))) "should have 2 repos")
      (is (some #{"test/alpha"} (registry/list-repos)) "alpha in list")
      (is (some #{"test/beta"}  (registry/list-repos)) "beta in list")

      ;; Read
      (let [entry (registry/get-repo "test/alpha")]
        (is (= "test/alpha" (:name entry)) "name roundtrip")
        (is (= "Test repo A" (:description entry)) "desc roundtrip")
        (is (= "service" (:role entry)) "role roundtrip"))

      ;; Update via re-add
      (registry/add! "test/alpha" {:description "Updated A"})
      (is (= "Updated A" (:description (registry/get-repo "test/alpha")))
          "update on re-add")
      (is (= "service" (:role (registry/get-repo "test/alpha")))
          "other fields preserved on update")

      ;; Remove
      (registry/remove! "test/beta")
      (is (nil? (registry/get-repo "test/beta")) "beta removed")
      (is (= 1 (count (registry/list-repos))) "one left")

      (finally
        (registry/save-registry! original)))))

(deftest test-registry-relationships
  (let [original (registry/load-registry)]
    (try
      (registry/save-registry! {})
      (registry/add! "test/app" {:role "frontend"})
      (registry/add! "test/api" {:role "backend"})
      (registry/add! "test/db"  {:role "database"})

      ;; Link
      (registry/link! "test/app" "test/api" :depends-on "REST calls")
      (registry/link! "test/api" "test/db"  :depends-on "Postgres")

      ;; Check outgoing
      (let [rels (registry/relationships "test/app")]
        (is (= 1 (count rels)) "app has 1 relationship")
        (is (= "test/api" (:to (first rels))) "linked to api")
        (is (= :depends-on (:type (first rels))) "correct type")
        (is (= "REST calls" (:note (first rels))) "note preserved"))

      ;; Related (both directions)
      (let [related (set (registry/related-repos "test/api"))]
        (is (contains? related "test/app") "api is related to app (incoming)")
        (is (contains? related "test/db")  "api is related to db (outgoing)"))

      ;; Removing a repo should clean incoming references
      (registry/remove! "test/db")
      (is (empty? (registry/relationships "test/api"))
          "api's relationships should be cleaned when db is removed")

      ;; Explicit unlink
      (registry/unlink! "test/app" "test/api")
      (is (empty? (registry/relationships "test/app")) "unlink works")

      (finally
        (registry/save-registry! original)))))

(deftest test-registry-briefing-context
  (let [original (registry/load-registry)]
    (try
      (registry/save-registry! {})
      (registry/add! "test/svc" {:description "Main service" :role "api"})
      (registry/add! "test/lib" {:description "Shared lib"})
      (registry/link! "test/svc" "test/lib" :depends-on "Uses for auth")

      (let [ctx (registry/relationship-context "test/svc")]
        (is (string? ctx) "context is a string")
        (is (str/includes? ctx "test/lib") "mentions related repo")
        (is (str/includes? ctx "Uses for auth") "includes the note"))

      (finally
        (registry/save-registry! original)))))

;; ---------------------------------------------------------------------------
;; Util tests
;; ---------------------------------------------------------------------------

(deftest test-atomic-spit
  (let [f (str (config/workdir) "/atomic-test.txt")]
    (util/atomic-spit f "hello world")
    (is (= "hello world" (slurp f)) "atomic-spit roundtrip")
    (util/atomic-spit f "replacement")
    (is (= "replacement" (slurp f)) "atomic-spit overwrite")
    (fs/delete-if-exists f)))

;; ---------------------------------------------------------------------------
;; Perch tests
;; ---------------------------------------------------------------------------

(deftest test-perch-time-ago
  (let [past (str (.minusSeconds (java.time.Instant/now) 120))]
    (is (str/includes? (perch/time-ago past) "m ago")
        "2 minutes ago should say 'Nm ago'"))
  (is (= "—" (perch/time-ago nil)) "nil returns dash"))

(deftest test-perch-renderers-return-strings
  (is (string? (perch/render-header)) "header renders")
  (is (string? (perch/render-walter)) "walter renders")
  (is (string? (perch/render-operations)) "operations renders")
  (is (string? (perch/render-abeyance)) "abeyance renders")
  (is (string? (perch/render-egress)) "egress renders")
  (is (string? (perch/page)) "full page renders")
  (is (str/includes? (perch/page) "SECTION") "page has title"))

(deftest test-perch-graph-data
  ;; Uses whatever is in the real registry — should return a valid vector
  (let [g (perch/graph-data)]
    (is (vector? g) "graph data is a vector")
    ;; Every element should have a :data key
    (doseq [el g]
      (is (contains? el :data) "element has :data"))))

(deftest test-perch-handler-status-endpoints
  (let [routes ["/" "/api/header" "/api/walter" "/api/operations"
                "/api/abeyance" "/api/egress" "/api/graph"]]
    (doseq [path routes]
      (let [resp (perch/handler {:request-method :get :uri path})]
        (is (= 200 (:status resp)) (str path " returns 200"))
        (is (string? (:body resp)) (str path " has a body"))))))

(deftest test-perch-handler-404
  (let [resp (perch/handler {:request-method :get :uri "/nonexistent"})]
    (is (= 404 (:status resp)) "unknown path returns 404")))

;; ---------------------------------------------------------------------------
;; Integration: structure check
;; ---------------------------------------------------------------------------

(deftest test-project-structure
  (let [root (:section-root config/config)]
    (doseq [f ["bb.edn" "birkoff.bb" "CLAUDE.md" "README.md"
                "walter/capabilities.edn" "madeline/memory.edn"
                "src/section/config.clj" "src/section/operations.clj"
                "src/section/comm.clj" "src/section/briefing.clj"
                "src/section/operative.clj" "src/section/walter.clj"
                "src/section/madeline.clj" "src/section/oversight.clj"
                "src/section/registry.clj" "src/section/util.clj"
                "src/section/perch.clj" "com.section.perch.plist"]]
      (is (fs/exists? (str root "/" f))
          (str "Missing file: " f)))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (println "\n=== Section Sims ===\n")
  (reset! failures [])
  (reset! passes 0)

  (test-config-loads)
  (test-ensure-dirs)
  (test-capabilities-edn-valid)
  (test-walter-check-all)
  (test-walter-capability-manifest)
  (test-madeline-memory-roundtrip)
  (test-madeline-mission-context)
  (test-briefing-assembly)
  (test-lock-lifecycle)
  (test-registry-crud)
  (test-registry-relationships)
  (test-registry-briefing-context)
  (test-atomic-spit)
  (test-perch-time-ago)
  (test-perch-renderers-return-strings)
  (test-perch-graph-data)
  (test-perch-handler-status-endpoints)
  (test-perch-handler-404)
  (test-project-structure)

  (println (str "\n" @passes " passed, " (count @failures) " failed."))
  (when (seq @failures)
    (println "\nFailures:")
    (doseq [{:keys [test error]} @failures]
      (println (str "  ✗ " test ": " error)))
    (System/exit 1))

  (println "\n=== All sims passed. Section is operational. ===\n"))
