(ns section.config
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Secrets — pulled from macOS Keychain, never stored on disk
;; ---------------------------------------------------------------------------

(defn get-secret
  "Retrieve a secret from macOS Keychain by service name."
  [service]
  (let [result (p/sh ["security" "find-generic-password"
                       "-a" "section"
                       "-s" service
                       "-w"])]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def section-root
  "Absolute path to the Section project root."
  (str (fs/canonicalize
         (or (System/getenv "SECTION_ROOT")
             ;; Detect project root by looking for bb.edn up from cwd
             (loop [dir (fs/cwd)]
               (cond
                 (fs/exists? (str dir "/bb.edn")) (str dir)
                 (nil? (fs/parent dir))            nil
                 :else (recur (fs/parent dir))))
             (str (System/getProperty "user.home") "/Sources/KB/section")))))

(def config
  "Central configuration map. Override via environment variables."
  {:bot-user   (or (System/getenv "SECTION_BOT_USER") "kbosompem")
   :label      (or (System/getenv "SECTION_LABEL")    "section")
   :repos      (if-let [r (System/getenv "SECTION_REPOS")]
                 (str/split r #",")
                 [])
   :workdir    (or (System/getenv "SECTION_WORKDIR")
                   (str (System/getProperty "user.home") "/section-workspace"))
   :max-turns  (parse-long (or (System/getenv "SECTION_MAX_TURNS") "25"))
   :timeout-ms (parse-long (or (System/getenv "SECTION_TIMEOUT_MS") "1800000")) ;; 30 min
   :pool-size  (parse-long (or (System/getenv "SECTION_POOL_SIZE") "4"))
   :section-root section-root})

(defn workdir     [] (:workdir config))
(defn locks-dir   [] (str (workdir) "/locks"))
(defn logs-dir    [] (str (workdir) "/logs"))
(defn repos-dir   [] (str (workdir) "/repos"))

(defn ensure-dirs!
  "Create all working directories."
  []
  (doseq [d [(workdir) (locks-dir) (logs-dir) (repos-dir)]]
    (fs/create-dirs d)))

;; ---------------------------------------------------------------------------
;; Environment for claude subprocess
;; ---------------------------------------------------------------------------

(defn claude-env
  "Build environment map for a claude -p subprocess."
  []
  (let [base (into {} (System/getenv))]
    (if-let [key (get-secret "anthropic-api-key")]
      (assoc base "ANTHROPIC_API_KEY" key)
      base)))
