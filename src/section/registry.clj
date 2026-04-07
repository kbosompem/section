(ns section.registry
  "The repo registry — which repos Section monitors and how they relate.
   Lives in madeline/repos.edn. Managed via `bb repo` subcommands."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [section.config :as config]
            [section.util :as util]))

(def registry-file
  (str (:section-root config/config) "/madeline/repos.edn"))

;; Valid relationship types — kept open but these are the blessed ones.
(def relationship-types
  #{:depends-on :used-by :monitors :deploys-to :tests-for
    :forks-from :parent-of :child-of :integrates-with :sibling-of})

;; ---------------------------------------------------------------------------
;; Load / save
;; ---------------------------------------------------------------------------

(defn load-registry
  "Load the repo registry. Empty map if it doesn't exist yet."
  []
  (if (fs/exists? registry-file)
    (edn/read-string (slurp registry-file))
    {}))

(defn save-registry!
  "Persist the registry atomically."
  [reg]
  (fs/create-dirs (fs/parent registry-file))
  (util/atomic-spit registry-file (with-out-str (clojure.pprint/pprint reg))))

(defn repo-key
  "Normalize a repo name to a keyword key. 'owner/repo' → :owner_repo"
  [repo]
  (keyword (str/replace repo "/" "_")))

(defn key->repo
  "Reverse: :owner_repo → 'owner/repo'"
  [k]
  (str/replace (name k) "_" "/"))

;; ---------------------------------------------------------------------------
;; CRUD
;; ---------------------------------------------------------------------------

(defn add!
  "Add a repo to the registry. Idempotent — updates description/role if present."
  [repo {:keys [description role tech]}]
  (let [reg (load-registry)
        k   (repo-key repo)
        existing (get reg k)
        entry (merge
                {:name repo
                 :relationships []
                 :added-at (str (java.time.Instant/now))}
                existing
                (cond-> {}
                  description (assoc :description description)
                  role        (assoc :role role)
                  tech        (assoc :tech tech))
                {:updated-at (str (java.time.Instant/now))})]
    (save-registry! (assoc reg k entry))
    entry))

(defn remove!
  "Remove a repo from the registry and strip any relationships pointing to it."
  [repo]
  (let [reg (load-registry)
        k   (repo-key repo)
        pruned (dissoc reg k)
        ;; Strip references from other repos' relationships
        cleaned (reduce-kv
                  (fn [acc k2 v]
                    (assoc acc k2
                      (update v :relationships
                        (fn [rels] (vec (remove #(= repo (:to %)) rels))))))
                  {} pruned)]
    (save-registry! cleaned)))

(defn get-repo
  "Look up a repo's entry."
  [repo]
  (get (load-registry) (repo-key repo)))

(defn list-repos
  "Return a sorted list of all registered repo names."
  []
  (->> (load-registry)
       keys
       (map key->repo)
       sort))

(defn monitored-repos
  "Return the list of repos Section should actively monitor."
  []
  (list-repos))

;; ---------------------------------------------------------------------------
;; Relationships
;; ---------------------------------------------------------------------------

(defn link!
  "Record that from-repo has a relationship to to-repo.
   Example: (link! \"kbosompem/app\" \"kbosompem/api\" :depends-on \"Uses the /v1 endpoints\")"
  [from-repo to-repo rel-type note]
  (when-not (relationship-types rel-type)
    (println (str "Registry: Warning — unusual relationship type: " rel-type)))
  (let [reg (load-registry)
        k (repo-key from-repo)]
    (when-not (get reg k)
      (throw (ex-info (str "Repo not in registry: " from-repo) {:repo from-repo})))
    (let [new-rel {:to to-repo :type rel-type :note note}
          updated (update-in reg [k :relationships]
                    (fn [rels]
                      (->> (or rels [])
                           ;; Replace existing relationship of same type to same repo
                           (remove #(and (= (:to %) to-repo)
                                         (= (:type %) rel-type)))
                           (cons new-rel)
                           vec)))]
      (save-registry! updated)
      new-rel)))

(defn unlink!
  "Remove a relationship between two repos (optionally filtered by type)."
  ([from-repo to-repo]
   (unlink! from-repo to-repo nil))
  ([from-repo to-repo rel-type]
   (let [reg (load-registry)
         k (repo-key from-repo)]
     (when (get reg k)
       (let [updated (update-in reg [k :relationships]
                       (fn [rels]
                         (vec (remove
                                (fn [r]
                                  (and (= (:to r) to-repo)
                                       (or (nil? rel-type)
                                           (= (:type r) rel-type))))
                                rels))))]
         (save-registry! updated))))))

(defn relationships
  "Get all relationships for a repo."
  [repo]
  (:relationships (get-repo repo) []))

(defn related-repos
  "Get repos directly related to this one (in either direction)."
  [repo]
  (let [reg (load-registry)
        entry (get reg (repo-key repo))
        outgoing (map :to (:relationships entry))
        incoming (for [[_ v] reg
                       r (:relationships v)
                       :when (= (:to r) repo)]
                   (:name v))]
    (distinct (concat outgoing incoming))))

;; ---------------------------------------------------------------------------
;; Briefing support — text output for Claude prompts
;; ---------------------------------------------------------------------------

(defn relationship-context
  "Produce a text summary of a repo's relationships for a briefing."
  [repo]
  (let [entry (get-repo repo)
        rels  (:relationships entry)
        incoming (for [[_ v] (load-registry)
                       r (:relationships v)
                       :when (= (:to r) repo)]
                   {:from (:name v) :type (:type r) :note (:note r)})]
    (if (and (empty? rels) (empty? incoming))
      ""
      (str "# Related repos for " repo "\n"
           (when (:description entry)
             (str "Description: " (:description entry) "\n"))
           (when (:role entry)
             (str "Role: " (:role entry) "\n"))
           (when (seq rels)
             (str "\nThis repo " (name (or (:type (first rels)) :relates-to)) ":\n"
                  (str/join "\n"
                    (for [r rels]
                      (str "  - " (name (:type r)) " → " (:to r)
                           (when (:note r) (str " (" (:note r) ")")))))
                  "\n"))
           (when (seq incoming)
             (str "\nOther repos point to this one:\n"
                  (str/join "\n"
                    (for [r incoming]
                      (str "  - " (:from r) " " (name (:type r)) " this repo"
                           (when (:note r) (str " (" (:note r) ")")))))
                  "\n"))))))

;; ---------------------------------------------------------------------------
;; CLI dispatcher — called from bb.edn
;; ---------------------------------------------------------------------------

(defn- print-repo [entry]
  (println (str "  " (:name entry)
                (when (:role entry) (str " [" (:role entry) "]"))))
  (when (:description entry)
    (println (str "    " (:description entry))))
  (when (seq (:tech entry))
    (println (str "    tech: " (str/join ", " (:tech entry)))))
  (when (seq (:relationships entry))
    (println "    relationships:")
    (doseq [r (:relationships entry)]
      (println (str "      " (name (:type r)) " → " (:to r)
                    (when (:note r) (str " — " (:note r))))))))

(defn- parse-opts
  "Parse --key value --key2 value2 into a map."
  [args]
  (loop [args args acc {}]
    (if (empty? args)
      acc
      (let [[k v & rest] args]
        (if (and (string? k) (str/starts-with? k "--"))
          (recur rest (assoc acc (keyword (subs k 2)) v))
          (recur (next args) acc))))))

(defn- cmd-add [args]
  (if-let [repo (first args)]
    (let [opts (parse-opts (rest args))
          entry (add! repo opts)]
      (println (str "Registry: Added " repo))
      (print-repo entry))
    (println "Usage: bb repo add owner/name [--description \"...\"] [--role \"...\"]")))

(defn- cmd-remove [args]
  (if-let [repo (first args)]
    (do (remove! repo)
        (println (str "Registry: Removed " repo)))
    (println "Usage: bb repo remove owner/name")))

(defn- cmd-list [_args]
  (let [repos (load-registry)]
    (if (empty? repos)
      (println "Registry: No repos registered. Use `bb repo add owner/name` to add one.")
      (do
        (println (str "=== Section Registry — " (count repos) " repo(s) ==="))
        (doseq [[_ entry] (sort-by (comp :name val) repos)]
          (print-repo entry))))))

(defn- cmd-show [args]
  (if-let [repo (first args)]
    (if-let [entry (get-repo repo)]
      (do
        (print-repo entry)
        (let [related (related-repos repo)]
          (when (seq related)
            (println (str "\n  related repos (all directions): " (str/join ", " related))))))
      (println (str "Registry: " repo " not found.")))
    (println "Usage: bb repo show owner/name")))

(defn- cmd-link [args]
  (let [[from to & opts] args
        opts (parse-opts opts)
        rel-type (keyword (or (:type opts) "depends-on"))
        note (:note opts)]
    (if (and from to)
      (do (link! from to rel-type note)
          (println (str "Registry: " from " --[" (name rel-type) "]--> " to)))
      (println "Usage: bb repo link from/repo to/repo [--type depends-on] [--note \"...\"]"))))

(defn- cmd-unlink [args]
  (let [[from to & opts] args
        opts (parse-opts opts)
        rel-type (when (:type opts) (keyword (:type opts)))]
    (if (and from to)
      (do (unlink! from to rel-type)
          (println (str "Registry: Unlinked " from " from " to)))
      (println "Usage: bb repo unlink from/repo to/repo [--type ...]"))))

(defn- print-help []
  (println "Section Registry — manage monitored repos and their relationships")
  (println "")
  (println "Commands:")
  (println "  bb repo add owner/name [--description \"...\"] [--role \"...\"]")
  (println "  bb repo list")
  (println "  bb repo show owner/name")
  (println "  bb repo remove owner/name")
  (println "  bb repo link from/repo to/repo [--type depends-on] [--note \"...\"]")
  (println "  bb repo unlink from/repo to/repo [--type ...]")
  (println "")
  (println "Relationship types:")
  (println (str "  " (str/join ", " (map name relationship-types)))))

(defn cli
  "Entry point for `bb repo ...` subcommands."
  [args]
  (let [[cmd & rest] args]
    (case cmd
      "add"    (cmd-add rest)
      "remove" (cmd-remove rest)
      "rm"     (cmd-remove rest)
      "list"   (cmd-list rest)
      "ls"     (cmd-list rest)
      "show"   (cmd-show rest)
      "link"   (cmd-link rest)
      "unlink" (cmd-unlink rest)
      "help"   (print-help)
      nil      (print-help)
      (do (println (str "Unknown command: " cmd))
          (print-help)))))
