(ns section.operations
  "Operations — the scheduler and dispatcher. He directs from The Perch."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [section.config :as config]
            [section.comm :as comm]
            [section.madeline :as madeline]
            [section.operative :as operative])
  (:import [java.util.concurrent Executors Callable TimeUnit]))

;; ---------------------------------------------------------------------------
;; Lock management — prevents duplicate work on the same issue
;; ---------------------------------------------------------------------------

(defn lock-file [repo number]
  (str (config/locks-dir) "/" (str/replace repo "/" "_") "_" number ".lock"))

(defn locked? [repo number]
  (fs/exists? (lock-file repo number)))

(defn lock! [repo number]
  (fs/create-dirs (config/locks-dir))
  (spit (lock-file repo number)
        (str (java.time.Instant/now))))

(defn unlock! [repo number]
  (fs/delete-if-exists (lock-file repo number)))

;; ---------------------------------------------------------------------------
;; Mission filtering — skip already-handled or in-flight issues
;; ---------------------------------------------------------------------------

(defn should-dispatch?
  "Determine if a mission should be dispatched."
  [repo issue]
  (let [number (get issue "number")
        mem    (madeline/get-mission repo number)]
    (and
      ;; Not locked (currently in-flight)
      (not (locked? repo number))
      ;; Not already completed
      (not= :completed (:status mem))
      ;; Not failed too many times (max 3 attempts)
      (or (nil? mem)
          (not= :failed (:status mem))
          (< (get mem :attempts 0) 3)))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn dispatch-mission!
  "Dispatch a single mission to an operative. Handles locking."
  [repo issue]
  (let [number (get issue "number")]
    (lock! repo number)
    (try
      (operative/execute! repo issue)
      (finally
        (unlock! repo number)))))

(defn dispatch-all!
  "Find all pending missions and dispatch them using a thread pool."
  []
  (let [missions  (comm/find-all-missions)
        pending   (filter #(should-dispatch? (get % "repo") %) missions)
        pool-size (:pool-size config/config)
        pool      (Executors/newFixedThreadPool pool-size)]
    (when (seq pending)
      (println (str "Operations: " (count pending) " mission(s) in abeyance. "
                    "Dispatching with " pool-size " operatives."))
      (let [callables (map (fn [issue]
                             (reify Callable
                               (call [_]
                                 (dispatch-mission! (get issue "repo") issue))))
                           pending)
            futures   (.invokeAll pool callables)]
        ;; Collect results
        (doseq [f futures]
          (try
            (.get f)
            (catch Exception e
              (println (str "Operations: Worker exception — " (.getMessage e))))))
        (.shutdown pool)
        (.awaitTermination pool 60 TimeUnit/MINUTES)))
    (when (empty? pending)
      (println "Operations: No missions in abeyance. Section stands ready."))))

;; ---------------------------------------------------------------------------
;; Status report (The Perch view)
;; ---------------------------------------------------------------------------

(defn perch-report
  "Print a status report of all missions."
  []
  (println "=== The Perch — Section Status ===")
  (let [mem (:missions (madeline/load-memory))]
    (if (empty? mem)
      (println "  No missions on record.")
      (doseq [[k v] (sort-by (comp :updated-at val) mem)]
        (println (str "  " (name k)
                      " | " (name (or (:status v) :unknown))
                      " | attempts: " (get v :attempts 0)
                      (when (:pr-url v) (str " | PR: " (:pr-url v)))
                      (when (:reason v) (str " | reason: " (:reason v)))))))
    (println (str "\n  Active locks: "
                  (count (fs/glob (config/locks-dir) "*.lock"))))))
