(ns section.oversight
  "Oversight — the watchdog. Self-healing, recovery, housekeeping."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [section.config :as config]
            [section.walter :as walter]
            [section.madeline :as madeline]
            [section.operations :as operations]
            [section.comm :as comm]))

;; ---------------------------------------------------------------------------
;; Stale lock cleanup
;; ---------------------------------------------------------------------------

(def max-lock-age-ms
  "Locks older than this are considered stale (1 hour)."
  (* 60 60 1000))

(defn clean-stale-locks!
  "Remove locks older than max-lock-age-ms."
  []
  (let [now (System/currentTimeMillis)]
    (doseq [lock (fs/glob (config/locks-dir) "*.lock")]
      (let [age (- now (.toMillis (fs/last-modified-time lock)))]
        (when (> age max-lock-age-ms)
          (println (str "Oversight: Cleaning stale lock — " (fs/file-name lock)))
          (fs/delete lock))))))

;; ---------------------------------------------------------------------------
;; Capability repair
;; ---------------------------------------------------------------------------

(defn repair-capabilities!
  "Check and repair broken required capabilities."
  []
  (let [broken (walter/missing-required)]
    (when (seq broken)
      (println "Oversight: Repairing missing required capabilities...")
      (doseq [[k v] broken]
        (walter/install! k v))
      (let [still-broken (walter/missing-required)]
        (when (seq still-broken)
          (println "Oversight: WARNING — Could not repair:")
          (doseq [[k _] still-broken]
            (println (str "  ✗ " (name k)))))))))

;; ---------------------------------------------------------------------------
;; Auth verification
;; ---------------------------------------------------------------------------

(defn check-auth!
  "Verify gh is authenticated AS the configured bot user. Returns true on
   match, false otherwise. Prints a loud, actionable warning on failure so
   the launchd stderr log captures exactly what's wrong."
  []
  (let [{:keys [ok? user expected reason]} (comm/auth-status)]
    (if ok?
      (do (println (str "Oversight: GitHub auth ✓ (" user ")")) true)
      (do (println (str "Oversight: WARNING — " reason "."))
          (println (str "  Fix: gh auth switch --user " expected
                        "  (or: gh auth login --user " expected ")"))
          false))))

(defn check-api-key!
  "Verify Anthropic API key is in Keychain. Returns true if present."
  []
  (if (config/get-secret "anthropic-api-key")
    (do (println "Oversight: Anthropic API key ✓") true)
    (do (println "Oversight: WARNING — No Anthropic API key in Keychain.")
        (println "  Run: security add-generic-password -a section -s anthropic-api-key -w YOUR_KEY")
        false)))

;; ---------------------------------------------------------------------------
;; Interrupted mission recovery
;; ---------------------------------------------------------------------------

(defn recover-interrupted!
  "Find missions that were in-progress but have stale locks (crashed).
   Reset them so they can be retried."
  []
  (let [in-progress (madeline/in-progress-missions)]
    (doseq [[k v] in-progress]
      (let [parts (str/split (name k) #"_")
            number (last parts)
            repo (str/join "/" (butlast parts))]
        ;; If there's no active lock, this was interrupted
        (when-not (operations/locked? repo number)
          (println (str "Oversight: Recovering interrupted mission — " (name k)))
          (madeline/save-mission! repo number
            {:status :interrupted :recovered true}))))))

;; ---------------------------------------------------------------------------
;; Full recovery sequence (run at startup)
;; ---------------------------------------------------------------------------

(defn recover!
  "Full startup recovery sequence. Runs every cycle, so it must stay quiet —
   any voice cue here would fire on every launchd tick. Auth verification
   has moved to birkoff.bb, where it gates the cycle, so a wrong-account
   state can't slip through and run missions."
  []
  (println "=== Oversight — Recovery Sequence ===")
  (config/ensure-dirs!)
  (clean-stale-locks!)
  (repair-capabilities!)
  (check-api-key!)
  (recover-interrupted!)
  (println "=== Oversight — Recovery Complete ===\n"))

;; ---------------------------------------------------------------------------
;; Housekeeping
;; ---------------------------------------------------------------------------

(defn prune-old-logs!
  "Delete mission logs older than 30 days."
  []
  (let [now (System/currentTimeMillis)
        max-age (* 30 24 60 60 1000)]
    (doseq [log (fs/glob (config/logs-dir) "*.log")]
      (when (> (- now (.toMillis (fs/last-modified-time log))) max-age)
        (println (str "Oversight: Pruning old log — " (fs/file-name log)))
        (fs/delete log)))))

(defn housekeeping!
  "Run all housekeeping tasks."
  []
  (println "=== Oversight — Housekeeping ===")
  (clean-stale-locks!)
  (prune-old-logs!)
  (println "=== Housekeeping Complete ==="))

;; ---------------------------------------------------------------------------
;; Status report
;; ---------------------------------------------------------------------------

(defn status-report
  "Full Section status report."
  []
  (println "\n=== Section Status Report ===\n")
  (walter/report)
  (println)
  (operations/perch-report)
  (println "\n=== End Report ==="))

(defn -main [& _args]
  (status-report))
