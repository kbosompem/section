(ns section.operative
  "Operative — a Claude Code worker. Executes missions."
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [section.config :as config]
            [section.briefing :as briefing]
            [section.comm :as comm]
            [section.madeline :as madeline]))

;; ---------------------------------------------------------------------------
;; Repo preparation
;; ---------------------------------------------------------------------------

(defn repo-dir
  "Local directory for a repo clone."
  [repo]
  (str (config/repos-dir) "/" (str/replace repo "/" "_")))

(defn ensure-repo!
  "Clone or update a repo. Returns the repo directory path."
  [repo]
  (let [dir (repo-dir repo)]
    (if (fs/exists? dir)
      (do
        (p/sh ["git" "fetch" "origin"] {:dir dir :timeout 60000})
        (let [default-branch (:out (p/sh ["git" "symbolic-ref" "refs/remotes/origin/HEAD"
                                           "--short"]
                                         {:dir dir :timeout 10000}))]
          (p/sh ["git" "checkout" (str/trim (str/replace (or default-branch "origin/main")
                                                          "origin/" ""))]
                 {:dir dir :timeout 10000})
          (p/sh ["git" "pull" "--ff-only"] {:dir dir :timeout 60000})))
      (p/sh ["gh" "repo" "clone" repo dir] {:timeout 120000}))
    dir))

(defn create-branch!
  "Create and checkout a mission branch."
  [dir number]
  (let [branch (str "section/issue-" number)]
    (p/sh ["git" "checkout" "-b" branch] {:dir dir :timeout 10000
                                           :continue true})
    branch))

;; ---------------------------------------------------------------------------
;; Run Claude
;; ---------------------------------------------------------------------------

(defn run-claude!
  "Run claude -p with the assembled briefing. Returns {:exit :out :err}."
  [dir {:keys [prompt system-prompt]}]
  (let [args (cond-> ["claude" "-p" prompt
                       "--max-turns" (str (:max-turns config/config))
                       "--dangerously-skip-permissions"]
               system-prompt
               (into ["--append-system-prompt" system-prompt]))]
    (p/sh args {:dir dir
                :timeout (:timeout-ms config/config)
                :env (config/claude-env)
                :err :string
                :out :string})))

;; ---------------------------------------------------------------------------
;; Post-mission: push, PR, comment
;; ---------------------------------------------------------------------------

(defn has-changes?
  "Check if there are uncommitted or new commits vs the default branch."
  [dir branch]
  (let [status (:out (p/sh ["git" "status" "--porcelain"] {:dir dir}))
        diff   (:exit (p/sh ["git" "diff" "--quiet" "HEAD" "origin/HEAD" "--"]
                             {:dir dir :continue true}))]
    (or (not (str/blank? status))
        (not (zero? diff)))))

(defn push-and-pr!
  "Push the branch and create a PR. Returns the PR URL or nil."
  [repo dir branch number title]
  (let [push-result (p/sh ["git" "push" "-u" "origin" branch]
                          {:dir dir :timeout 60000
                           :err :string :out :string})]
    (when (zero? (:exit push-result))
      (comm/create-pr! repo branch
        (str "section: " title)
        (str "Closes #" number "\n\n"
             "Automated implementation by Section operative.")))))

;; ---------------------------------------------------------------------------
;; Execute a full mission
;; ---------------------------------------------------------------------------

(defn execute!
  "Execute a mission end-to-end. Returns {:status :pr-url :output}."
  [repo issue]
  (let [number (get issue "number")
        title  (get issue "title")]
    (println (str "Operative: Starting mission " repo "#" number " — " title))

    ;; Mark in-progress in Madeline
    (madeline/save-mission! repo number
      {:status :in-progress :title title :branch (str "section/issue-" number)})

    ;; Notify on the issue
    (comm/comment-on-issue! repo number
      "Section operative reporting for duty. Mission commencing.")

    (try
      ;; Prepare repo and branch
      (let [dir     (ensure-repo! repo)
            branch  (create-branch! dir number)
            brief   (briefing/assemble repo dir issue)
            result  (run-claude! dir brief)
            output  (:out result)
            log-file (str (config/logs-dir) "/"
                          (str/replace repo "/" "_") "_" number ".log")]

        ;; Save the log
        (spit log-file (str "=== Mission " repo "#" number " ===\n"
                            "Exit: " (:exit result) "\n"
                            "=== STDOUT ===\n" output "\n"
                            "=== STDERR ===\n" (:err result) "\n"))

        (if (zero? (:exit result))
          ;; Success — push and PR
          (if (has-changes? dir branch)
            (let [pr-url (push-and-pr! repo dir branch number title)]
              (if pr-url
                (do
                  (comm/comment-on-issue! repo number
                    (str "Mission complete. PR submitted: " pr-url))
                  (madeline/complete-mission! repo number
                    {:pr-url pr-url :summary (subs output 0 (min 500 (count output)))})
                  {:status :completed :pr-url pr-url :output output})
                (do
                  (comm/comment-on-issue! repo number
                    "Mission complete but failed to create PR. Check logs.")
                  (madeline/fail-mission! repo number "PR creation failed")
                  {:status :failed :reason "PR creation failed" :output output})))
            (do
              (comm/comment-on-issue! repo number
                "Investigated but found no changes to make. May need human review.")
              (madeline/complete-mission! repo number
                {:summary "No changes needed"})
              {:status :no-changes :output output}))

          ;; Claude exited non-zero
          (do
            (comm/comment-on-issue! repo number
              (str "Mission failed (exit " (:exit result) "). "
                   "Check Section logs for details."))
            (madeline/fail-mission! repo number
              (str "claude exit " (:exit result)))
            {:status :failed :reason (str "exit " (:exit result)) :output output})))

      (catch Exception e
        (let [msg (.getMessage e)]
          (println (str "Operative: Mission " repo "#" number " exception: " msg))
          (comm/comment-on-issue! repo number
            (str "Mission aborted due to error: " msg))
          (madeline/fail-mission! repo number msg)
          {:status :error :reason msg})))))
