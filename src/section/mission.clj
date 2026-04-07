(ns section.mission
  "Mission helpers — claim/unclaim/list issues across registered repos.
   Claiming = adding the section label so Birkoff picks it up on the next cycle."
  (:require [babashka.process :as p]
            [babashka.json :as json]
            [clojure.string :as str]
            [section.config :as config]
            [section.registry :as registry]
            [section.comm :as comm]))

;; ---------------------------------------------------------------------------
;; Claim / unclaim
;; ---------------------------------------------------------------------------

(defn claim!
  "Add the section label to an issue. Returns true on success."
  [repo number]
  (let [label (:label config/config)
        r (p/sh ["gh" "issue" "edit" (str number)
                  "--repo" repo
                  "--add-label" label]
                 {:timeout 15000
                  :err :string :out :string})]
    (zero? (:exit r))))

(defn unclaim!
  "Remove the section label from an issue."
  [repo number]
  (let [label (:label config/config)
        r (p/sh ["gh" "issue" "edit" (str number)
                  "--repo" repo
                  "--remove-label" label]
                 {:timeout 15000
                  :err :string :out :string})]
    (zero? (:exit r))))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn missions-by-repo
  "Find labeled missions across all registered repos.
   Returns [[repo issues] ...] in registry order."
  []
  (for [repo (registry/list-repos)]
    [repo (comm/find-missions repo)]))

(defn total-count
  "Total claimed missions across all repos."
  []
  (reduce + 0 (map (comp count second) (missions-by-repo))))

;; ---------------------------------------------------------------------------
;; CLI dispatcher
;; ---------------------------------------------------------------------------

(defn- cmd-list [_args]
  (let [by-repo (missions-by-repo)
        total   (reduce + 0 (map (comp count second) by-repo))]
    (if (zero? total)
      (println (str "No claimed missions across "
                    (count by-repo) " registered repo(s).\n"
                    "Use `bb mission claim <repo> <number>` to label an issue."))
      (do
        (println (str "=== " total " claimed mission(s) across "
                      (count (filter (comp seq second) by-repo)) " repo(s) ==="))
        (doseq [[repo issues] by-repo
                :when (seq issues)]
          (println (str "\n" repo))
          (doseq [issue issues]
            (let [n        (:number issue)
                  title    (:title issue)
                  ass      (or (:assignees issue) [])
                  ass-str  (when (seq ass)
                             (str " (assigned: "
                                  (str/join ", " (map :login ass))
                                  ")"))]
              (println (str "  #" n "  " title (or ass-str ""))))))))))

(defn- cmd-claim [args]
  (let [[repo number] args]
    (if (and repo number)
      (if (claim! repo number)
        (println (str "Mission: claimed " repo "#" number ". Birkoff will pick it up on the next cycle."))
        (println (str "Mission: failed to claim " repo "#" number)))
      (println "Usage: bb mission claim <owner/repo> <issue-number>"))))

(defn- cmd-unclaim [args]
  (let [[repo number] args]
    (if (and repo number)
      (if (unclaim! repo number)
        (println (str "Mission: unclaimed " repo "#" number "."))
        (println (str "Mission: failed to unclaim " repo "#" number)))
      (println "Usage: bb mission unclaim <owner/repo> <issue-number>"))))

(defn- print-help []
  (println "Section Missions — manage what Birkoff works on")
  (println "")
  (println "Commands:")
  (println "  bb mission list                       Show all claimed missions across registered repos")
  (println "  bb mission claim   <owner/repo> <#>   Add the 'section' label so Birkoff picks it up")
  (println "  bb mission unclaim <owner/repo> <#>   Remove the 'section' label")
  (println "")
  (println "Notes:")
  (println "  - Birkoff dispatches any open issue labeled 'section' on a registered repo.")
  (println "  - You can also label issues directly via the GitHub web UI or `gh issue edit`."))

(defn cli
  "Entry point for `bb mission ...` subcommands."
  [args]
  (let [[cmd & rest] args]
    (case cmd
      "list"    (cmd-list rest)
      "ls"      (cmd-list rest)
      "claim"   (cmd-claim rest)
      "unclaim" (cmd-unclaim rest)
      "help"    (print-help)
      nil       (cmd-list nil)
      (do (println (str "Unknown command: " cmd))
          (print-help)))))
