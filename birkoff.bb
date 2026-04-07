#!/usr/bin/env bb
;; Birkoff — Section's nerve center.
;; Run once: bb run
;; Scheduled via launchd for continuous operation.

(require '[section.config :as config]
         '[section.oversight :as oversight]
         '[section.operations :as operations]
         '[section.walter :as walter]
         '[section.util :as util]
         '[babashka.fs :as fs])

(defn heartbeat-file []
  (str (config/workdir) "/heartbeat.edn"))

(defn write-heartbeat! [phase]
  (fs/create-dirs (config/workdir))
  (util/atomic-spit (heartbeat-file)
    (pr-str {:phase phase
             :timestamp (str (java.time.Instant/now))
             :pid (.pid (java.lang.ProcessHandle/current))})))

(defn -main []
  (let [start (java.time.Instant/now)]
    (println (str "\n[" start "] Birkoff online.\n"))
    (write-heartbeat! :online)

    ;; Phase 1: Recovery & self-check
    (oversight/recover!)

    ;; Phase 2: Verify required capabilities
    (let [missing (walter/missing-required)]
      (when (seq missing)
        (println "Birkoff: ABORT — missing required capabilities:")
        (doseq [[k _] missing]
          (println (str "  ✗ " (name k))))
        (write-heartbeat! :aborted)
        (System/exit 1)))

    ;; Phase 3: Dispatch missions
    (write-heartbeat! :dispatching)
    (operations/dispatch-all!)

    ;; Phase 4: Housekeeping
    (write-heartbeat! :housekeeping)
    (oversight/housekeeping!)

    (write-heartbeat! :idle)
    (println (str "\n[" (java.time.Instant/now) "] Birkoff signing off.\n"))))

(-main)
