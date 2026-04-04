(ns section.comm
  "Comm — communications. GitHub polling and email-to-issue bridge."
  (:require [babashka.process :as p]
            [babashka.json :as json]
            [clojure.string :as str]
            [section.config :as config]))

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
  "Find open issues assigned to the bot user with the section label in a repo."
  [repo]
  (let [bot-user (:bot-user config/config)
        label    (:label config/config)]
    (or (gh-json "gh" "issue" "list"
                 "--repo" repo
                 "--assignee" bot-user
                 "--label" label
                 "--state" "open"
                 "--json" "number,title,body,labels,comments,createdAt")
        [])))

(defn find-all-missions
  "Find missions across all configured repos."
  []
  (mapcat (fn [repo]
            (map #(assoc % "repo" repo)
                 (find-missions repo)))
          (:repos config/config)))

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
  "Create a pull request."
  [repo branch title body]
  (let [r (p/sh ["gh" "pr" "create"
                  "--repo" repo
                  "--head" branch
                  "--title" title
                  "--body" body]
                 {:timeout 30000
                  :err :string
                  :out :string})]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

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
