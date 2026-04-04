(ns section.sim-test
  "Sims — Section's test suite. Run before every egress."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [section.config :as config]
            [section.walter :as walter]
            [section.madeline :as madeline]
            [section.briefing :as briefing]
            [section.operations :as ops]))

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
;; Integration: structure check
;; ---------------------------------------------------------------------------

(deftest test-project-structure
  (let [root (:section-root config/config)]
    (doseq [f ["bb.edn" "birkoff.bb" "CLAUDE.md" "README.md"
                "walter/capabilities.edn" "madeline/memory.edn"
                "src/section/config.clj" "src/section/operations.clj"
                "src/section/comm.clj" "src/section/briefing.clj"
                "src/section/operative.clj" "src/section/walter.clj"
                "src/section/madeline.clj" "src/section/oversight.clj"]]
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
  (test-project-structure)

  (println (str "\n" @passes " passed, " (count @failures) " failed."))
  (when (seq @failures)
    (println "\nFailures:")
    (doseq [{:keys [test error]} @failures]
      (println (str "  ✗ " test ": " error)))
    (System/exit 1))

  (println "\n=== All sims passed. Section is operational. ===\n"))
