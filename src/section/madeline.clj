(ns section.madeline
  "Madeline — the memory system. She knows everything about everyone."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [section.config :as config]))

(def memory-file
  (str (:section-root config/config) "/madeline/memory.edn"))

;; ---------------------------------------------------------------------------
;; Core CRUD
;; ---------------------------------------------------------------------------

(defn load-memory
  "Load the full memory store."
  []
  (if (fs/exists? memory-file)
    (edn/read-string (slurp memory-file))
    {:missions {} :knowledge {} :repos {}}))

(defn save-memory!
  "Persist the full memory store."
  [mem]
  (spit memory-file (pr-str mem)))

(defn update-memory!
  "Apply f to the memory store and persist."
  [f & args]
  (let [mem (apply f (load-memory) args)]
    (save-memory! mem)
    mem))

;; ---------------------------------------------------------------------------
;; Mission memory — tracks per-issue state across runs
;; ---------------------------------------------------------------------------

(defn mission-key [repo number]
  (keyword (str (str/replace repo "/" "_") "_" number)))

(defn get-mission
  "Get memory for a specific mission (repo + issue number)."
  [repo number]
  (get-in (load-memory) [:missions (mission-key repo number)]))

(defn save-mission!
  "Save/update memory for a specific mission."
  [repo number data]
  (update-memory!
    assoc-in [:missions (mission-key repo number)]
    (merge (get-mission repo number)
           data
           {:updated-at (str (java.time.Instant/now))})))

(defn complete-mission!
  "Mark a mission as completed."
  [repo number data]
  (save-mission! repo number
    (merge data {:status :completed
                 :completed-at (str (java.time.Instant/now))})))

(defn fail-mission!
  "Mark a mission as failed with a reason."
  [repo number reason]
  (let [existing (get-mission repo number)
        attempts (inc (get existing :attempts 0))]
    (save-mission! repo number
      {:status :failed
       :reason reason
       :attempts attempts})))

(defn in-progress-missions
  "Return all missions currently in progress."
  []
  (->> (:missions (load-memory))
       (filter (fn [[_ v]] (= :in-progress (:status v))))
       (into {})))

;; ---------------------------------------------------------------------------
;; Knowledge — cross-repo learnings
;; ---------------------------------------------------------------------------

(defn add-knowledge!
  "Store a cross-repo learning."
  [key info]
  (update-memory!
    assoc-in [:knowledge key]
    (merge info {:learned-at (str (java.time.Instant/now))})))

(defn get-knowledge
  "Retrieve a specific piece of knowledge."
  [key]
  (get-in (load-memory) [:knowledge key]))

;; ---------------------------------------------------------------------------
;; Repo memory — per-repo context
;; ---------------------------------------------------------------------------

(defn save-repo-context!
  "Store context about a specific repo."
  [repo data]
  (update-memory!
    assoc-in [:repos (keyword (str/replace repo "/" "_"))]
    (merge data {:updated-at (str (java.time.Instant/now))})))

(defn get-repo-context
  "Get stored context for a repo."
  [repo]
  (get-in (load-memory) [:repos (keyword (str/replace repo "/" "_"))]))

;; ---------------------------------------------------------------------------
;; Briefing support — produce memory context for Claude prompts
;; ---------------------------------------------------------------------------

(defn mission-context
  "Produce a text summary of prior mission attempts for a briefing."
  [repo number]
  (if-let [m (get-mission repo number)]
    (str "Prior attempts on this issue:\n"
         "  Status: " (name (or (:status m) :unknown)) "\n"
         "  Attempts: " (get m :attempts 0) "\n"
         (when (:reason m)
           (str "  Last failure: " (:reason m) "\n"))
         (when (:summary m)
           (str "  Summary: " (:summary m) "\n")))
    "No prior work on this issue."))

(defn repo-context-text
  "Produce a text summary of repo knowledge for a briefing."
  [repo]
  (if-let [ctx (get-repo-context repo)]
    (str "Known context for " repo ":\n"
         (str/join "\n"
           (for [[k v] (dissoc ctx :updated-at)]
             (str "  " (name k) ": " v))))
    ""))
