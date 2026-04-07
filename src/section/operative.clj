(ns section.operative
  "Operative — a Claude Code worker. Executes missions."
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [section.config :as config]
            [section.briefing :as briefing]
            [section.comm :as comm]
            [section.madeline :as madeline]
            [section.voice :as voice]))

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

(defn current-branch
  "Return the currently-checked-out branch name in dir."
  [dir]
  (str/trim (:out (p/sh ["git" "branch" "--show-current"]
                         {:dir dir :err :string :out :string}))))

(defn create-branch!
  "Create and checkout a mission branch from current HEAD.
   Always starts fresh — any stale local branch with the same name is
   deleted first so we never inherit stale state. Throws if we end up
   on the wrong branch (defense against silent git failures)."
  [dir number]
  (let [branch (str "section/issue-" number)]
    ;; Nuke any stale local branch with the same name. ensure-repo!
    ;; has already put us on the default branch, so this is safe.
    (p/sh ["git" "branch" "-D" branch]
          {:dir dir :continue true :err :string :out :string})
    ;; Create fresh from current HEAD (which is the default branch)
    (let [r (p/sh ["git" "checkout" "-b" branch]
                   {:dir dir :timeout 10000
                    :err :string :out :string})]
      (when-not (zero? (:exit r))
        (throw (ex-info (str "Failed to create branch " branch ": " (:err r))
                 {:branch branch :exit (:exit r)}))))
    ;; Verify we're actually on it. If git ever lies, we want to know loudly.
    (let [actual (current-branch dir)]
      (when-not (= actual branch)
        (throw (ex-info (str "Expected to be on " branch " but on " actual)
                 {:expected branch :actual actual}))))
    branch))

(defn assert-on-branch!
  "Throw if dir is not currently on the expected branch.
   Used as a safety check before pushing."
  [dir branch]
  (let [actual (current-branch dir)]
    (when-not (= actual branch)
      (throw (ex-info (str "Refusing to push: expected branch " branch
                            " but currently on " actual)
               {:expected branch :actual actual})))))

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
  "Push the branch and create a PR. Returns the PR URL or nil.
   Refuses to push if the operative left us on the wrong branch."
  [repo dir branch number title]
  (assert-on-branch! dir branch)
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
  (let [number (:number issue)
        title  (:title issue)]
    (println (str "Operative: Starting mission " repo "#" number " — " title))
    (voice/speak-event! :mission-start repo number)

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
                  (voice/speak-event! :mission-done repo number)
                  {:status :completed :pr-url pr-url :output output})
                (do
                  (comm/comment-on-issue! repo number
                    "Mission complete but failed to create PR. Check logs.")
                  (madeline/fail-mission! repo number "PR creation failed")
                  (voice/speak-event! :mission-failed repo number)
                  {:status :failed :reason "PR creation failed" :output output})))
            (do
              (comm/comment-on-issue! repo number
                "Investigated but found no changes to make. May need human review.")
              (madeline/complete-mission! repo number
                {:summary "No changes needed"})
              (voice/speak-event! :mission-no-changes repo number)
              {:status :no-changes :output output}))

          ;; Claude exited non-zero
          (do
            (comm/comment-on-issue! repo number
              (str "Mission failed (exit " (:exit result) "). "
                   "Check Section logs for details."))
            (madeline/fail-mission! repo number
              (str "claude exit " (:exit result)))
            (voice/speak-event! :mission-failed repo number)
            {:status :failed :reason (str "exit " (:exit result)) :output output})))

      (catch Exception e
        (let [msg (.getMessage e)]
          (println (str "Operative: Mission " repo "#" number " exception: " msg))
          (comm/comment-on-issue! repo number
            (str "Mission aborted due to error: " msg))
          (madeline/fail-mission! repo number msg)
          {:status :error :reason msg})))))
