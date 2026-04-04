#!/usr/bin/env bb
;; Birkoff — Section's nerve center.
;; Run once: bb run
;; Scheduled via launchd for continuous operation.

(require '[section.config :as config]
         '[section.oversight :as oversight]
         '[section.operations :as operations]
         '[section.walter :as walter])

(defn -main []
  (println (str "\n[" (java.time.Instant/now) "] Birkoff online.\n"))

  ;; Phase 1: Recovery & self-check
  (oversight/recover!)

  ;; Phase 2: Verify required capabilities
  (let [missing (walter/missing-required)]
    (when (seq missing)
      (println "Birkoff: ABORT — missing required capabilities:")
      (doseq [[k _] missing]
        (println (str "  ✗ " (name k))))
      (System/exit 1)))

  ;; Phase 3: Dispatch missions
  (operations/dispatch-all!)

  ;; Phase 4: Housekeeping
  (oversight/housekeeping!)

  (println (str "\n[" (java.time.Instant/now) "] Birkoff signing off.\n")))

(-main)
