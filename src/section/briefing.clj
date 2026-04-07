(ns section.briefing
  "Briefing — assemble the full mission prompt for an operative."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [section.config :as config]
            [section.walter :as walter]
            [section.madeline :as madeline]
            [section.registry :as registry]
            [section.comm :as comm]))

(defn read-repo-claude-md
  "Read the CLAUDE.md from a repo's working directory, if it exists."
  [repo-dir]
  (let [f (str repo-dir "/CLAUDE.md")]
    (when (fs/exists? f)
      (slurp f))))

(defn issue-context
  "Build context string from the issue body and comments."
  [_repo issue]
  (let [number   (:number issue)
        title    (:title issue)
        body     (:body issue)
        comments (or (:comments issue) [])]
    (str "## GitHub Issue #" number "\n"
         "**Title:** " title "\n\n"
         "**Description:**\n" (or body "(no description)") "\n\n"
         (when (seq comments)
           (str "**Discussion:**\n"
                (str/join "\n---\n"
                  (map (fn [c]
                         (str (or (:login (:author c)) "unknown")
                              ": " (or (:body c) "")))
                       comments))
                "\n\n")))))

(defn build-system-prompt
  "Build the --append-system-prompt content for a mission."
  [repo repo-dir issue]
  (let [number (:number issue)]
    (str/join "\n\n"
      (remove str/blank?
        [(str "# Section Operative Briefing\n"
              "You are an operative of Section. Complete the mission below.\n"
              "After implementing, commit your changes with a clear message referencing issue #" number ".")

         ;; Capabilities
         (walter/capability-manifest)

         ;; Repo CLAUDE.md
         (when-let [md (read-repo-claude-md repo-dir)]
           (str "# Repo Guidelines (CLAUDE.md)\n" md))

         ;; Memory from prior attempts
         (madeline/mission-context repo number)

         ;; Repo-level memory
         (madeline/repo-context-text repo)

         ;; Repo relationships from the registry
         (registry/relationship-context repo)

         ;; Standing orders
         "# Standing Orders
- If you need a tool that isn't installed, install it via the command shown in the capability list.
- Write tests when appropriate.
- If the issue asks you to modify Section itself, edit the Babashka source files and run `bb test` before committing.
- Always commit before finishing. Use a descriptive commit message.
- If you cannot complete the mission, explain why in your final output."]))))

(defn build-prompt
  "Build the main -p prompt for a mission."
  [repo issue]
  (let [number (:number issue)
        title  (:title issue)
        body   (:body issue)]
    (str "You are working on GitHub issue #" number " in repo " repo ".\n\n"
         "Title: " title "\n"
         "Description:\n" (or body "(no description)") "\n\n"
         "Read the codebase, understand the issue, implement the solution, "
         "write tests if appropriate, and commit your changes.\n"
         "Reference issue #" number " in your commit message.")))

(defn assemble
  "Assemble a full briefing for a mission. Returns {:prompt :system-prompt}."
  [repo repo-dir issue]
  {:prompt        (build-prompt repo issue)
   :system-prompt (build-system-prompt repo repo-dir issue)})
