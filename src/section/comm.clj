(ns section.comm
  "Comm — communications. GitHub polling and email-to-issue bridge."
  (:require [babashka.process :as p]
            [babashka.json :as json]
            [clojure.string :as str]
            [section.config :as config]
            [section.registry :as registry]))

;; ---------------------------------------------------------------------------
;; gh auth identity — verify Section is talking to the right account
;; ---------------------------------------------------------------------------

(defn current-gh-user
  "Return the login of the currently authenticated gh user, or nil if gh is
   not authenticated / not installed. Pure: no printing, no side effects
   beyond the subprocess call."
  []
  (try
    (let [r (p/sh ["gh" "api" "user" "--jq" ".login"]
                  {:timeout 10000 :err :string :out :string})]
      (when (zero? (:exit r))
        (let [s (str/trim (:out r))]
          (when (seq s) s))))
    (catch Exception _ nil)))

(defn auth-status
  "Compare the currently-authenticated gh user against the configured
   :bot-user and return a status map. Never throws.
     {:ok? true  :expected E :user E}
     {:ok? false :expected E :user nil       :reason \"...\"}
     {:ok? false :expected E :user \"other\" :reason \"...\"}"
  []
  (let [expected (:bot-user config/config)
        actual   (current-gh-user)]
    (cond
      (nil? actual)
      {:ok? false :expected expected :user nil
       :reason (str "gh is not authenticated; expected " expected)}

      (not= actual expected)
      {:ok? false :expected expected :user actual
       :reason (str "gh is authenticated as '" actual
                    "' but Section is configured for '" expected "'")}

      :else
      {:ok? true :expected expected :user actual})))

;; ---------------------------------------------------------------------------
;; GitHub issue polling
;; ---------------------------------------------------------------------------

(defn gh-json
  "Run a gh CLI command and parse JSON output."
  [& args]
  (let [result (p/sh (vec args)
                     {:timeout 30000
                      :err :string
                      :out :string})]
    (when (zero? (:exit result))
      (json/read-str (:out result)))))

(defn find-missions
  "Find open issues with the section label in a repo.
   The label is the explicit opt-in — assignee is no longer required.
   Use `bb mission claim <repo> <number>` to label an issue."
  [repo]
  (let [label (:label config/config)]
    (or (gh-json "gh" "issue" "list"
                 "--repo" repo
                 "--label" label
                 "--state" "open"
                 "--json" "number,title,body,labels,comments,createdAt,assignees")
        [])))

(defn active-repos
  "Resolve the list of repos to monitor.
   Prefers the registry; falls back to SECTION_REPOS env var for bootstrap."
  []
  (let [registered (registry/monitored-repos)]
    (if (seq registered)
      registered
      (:repos config/config))))

(defn find-all-missions
  "Find missions across all monitored repos."
  []
  (mapcat (fn [repo]
            (map #(assoc % :repo repo)
                 (find-missions repo)))
          (active-repos)))

(defn get-issue-comments
  "Get all comments on an issue."
  [repo number]
  (or (gh-json "gh" "issue" "view" (str number)
               "--repo" repo
               "--json" "comments"
               "--jq" ".comments")
      []))

(defn comment-on-issue!
  "Post a comment on a GitHub issue."
  [repo number body]
  (p/sh ["gh" "issue" "comment" (str number)
         "--repo" repo
         "--body" body]
        {:timeout 15000}))

(defn add-label!
  "Add a label to an issue."
  [repo number label]
  (p/sh ["gh" "issue" "edit" (str number)
         "--repo" repo
         "--add-label" label]
        {:timeout 15000}))

(defn remove-label!
  "Remove a label from an issue."
  [repo number label]
  (p/sh ["gh" "issue" "edit" (str number)
         "--repo" repo
         "--remove-label" label]
        {:timeout 15000}))

;; ---------------------------------------------------------------------------
;; Repo management
;; ---------------------------------------------------------------------------

(defn create-repo!
  "Create a new GitHub repo under the configured user/org."
  [name {:keys [description public? template]}]
  (let [args (cond-> ["gh" "repo" "create" name
                       "--description" (or description "")]
               public?  (conj "--public")
               (not public?) (conj "--private")
               template (conj "--template" template)
               true     (conj "--clone"))]
    (let [r (p/sh args {:dir (config/repos-dir)
                        :timeout 60000
                        :err :string
                        :out :string})]
      (when (zero? (:exit r))
        (println (str "Comm: Created repo " name))
        true))))

(defn invite-collaborator!
  "Invite a user to a repo with given permission."
  [repo username permission]
  (let [r (p/sh ["gh" "api"
                  (str "repos/" repo "/collaborators/" username)
                  "-X" "PUT"
                  "-f" (str "permission=" (or permission "push"))]
                 {:timeout 15000
                  :err :string
                  :out :string})]
    (zero? (:exit r))))

(defn add-to-project!
  "Add a repo or issue URL to a GitHub Project."
  [project-number owner url]
  (p/sh ["gh" "project" "item-add" (str project-number)
         "--owner" owner
         "--url" url]
        {:timeout 15000}))

;; ---------------------------------------------------------------------------
;; PR creation
;; ---------------------------------------------------------------------------

(defn create-pr!
  "Create a pull request. Returns a map describing the result so callers
   can surface failure detail (gh's stderr) instead of throwing it away.
   Shape:
     {:ok? true  :url \"https://...\" :exit 0 :stdout ... :stderr ...}
     {:ok? false :exit N             :stdout ... :stderr ...}"
  [repo branch title body]
  (let [r (p/sh ["gh" "pr" "create"
                  "--repo" repo
                  "--head" branch
                  "--title" title
                  "--body" body]
                 {:timeout 30000
                  :err :string
                  :out :string})
        ok? (zero? (:exit r))]
    (cond-> {:ok? ok?
             :exit (:exit r)
             :stdout (:out r)
             :stderr (:err r)}
      ok? (assoc :url (str/trim (:out r))))))

;; ---------------------------------------------------------------------------
;; Email bridge (optional — Gmail IMAP → GitHub issue)
;; ---------------------------------------------------------------------------

(defn email-to-issues!
  "Check Gmail for unread messages with 'section:' in subject,
   create GitHub issues from them. Requires javax.mail pod or bb http.
   This is a stub — implement when email bridge is needed."
  []
  ;; TODO: Implement when needed. For now, issues go straight to GitHub.
  (println "Comm: Email bridge not yet implemented. File issues on GitHub directly."))
