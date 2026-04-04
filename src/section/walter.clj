(ns section.walter
  "Walter — the capability registry. He builds the gadgets."
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [section.config :as config]))

(def capabilities-file
  (str (:section-root config/config) "/walter/capabilities.edn"))

(defn load-capabilities
  "Load the capability registry from walter/capabilities.edn."
  []
  (edn/read-string (slurp capabilities-file)))

(defn check-capability
  "Test whether a capability is available. Returns true/false."
  [_name spec]
  (try
    (let [r (p/sh (:check spec) {:timeout 15000
                                  :err :string
                                  :out :string})]
      (zero? (:exit r)))
    (catch Exception _ false)))

(defn check-all
  "Check all capabilities. Returns map of name → {:spec ... :available? bool}."
  []
  (let [caps (load-capabilities)]
    (->> caps
         (map (fn [[k v]]
                [k (assoc v :available? (check-capability k v))]))
         (into (sorted-map)))))

(defn available
  "Return only available capabilities."
  []
  (->> (check-all)
       (filter (fn [[_ v]] (:available? v)))
       (into {})))

(defn missing
  "Return capabilities that are not available."
  []
  (->> (check-all)
       (filter (fn [[_ v]] (not (:available? v))))
       (into {})))

(defn missing-required
  "Return required capabilities that are not available."
  []
  (->> (missing)
       (filter (fn [[_ v]] (:required v)))
       (into {})))

(defn install!
  "Attempt to install a missing capability. Returns true on success."
  [name spec]
  (println (str "Walter: Installing " (clojure.core/name name) "..."))
  (let [r (p/sh ["bash" "-c" (:install spec)]
                 {:timeout 300000
                  :err :string
                  :out :string})]
    (if (zero? (:exit r))
      (do (println (str "Walter: " (clojure.core/name name) " installed."))
          true)
      (do (println (str "Walter: Failed to install " (clojure.core/name name)))
          (println (:err r))
          false))))

(defn repair!
  "Try to install all missing capabilities. Returns list of still-missing."
  []
  (let [m (missing)]
    (doseq [[k v] m]
      (install! k v))
    (missing)))

(defn capability-manifest
  "Produce a text summary of capabilities for Claude briefings."
  []
  (let [caps (check-all)]
    (str "Available tools on this machine:\n"
         (str/join "\n"
           (for [[k v] caps]
             (str "  - " (name k) ": " (:description v)
                  (if (:available? v) " [INSTALLED]" " [NOT INSTALLED]"))))
         "\n\nTo install a missing tool, run its install command via bash.\n"
         "To add a new capability, edit walter/capabilities.edn in the Section repo.")))

(defn report
  "Print a capabilities report."
  []
  (println "=== Walter — Capability Report ===")
  (let [caps (check-all)]
    (doseq [[k v] caps]
      (println (str "  " (if (:available? v) "✓" "✗")
                    " " (name k) " — " (:description v))))
    (let [m (missing-required)]
      (when (seq m)
        (println "\n⚠ Missing required capabilities:")
        (doseq [[k _] m]
          (println (str "    " (name k))))))))

(defn -main [& _args]
  (report))
