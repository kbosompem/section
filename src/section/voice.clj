(ns section.voice
  "Voice — Birkoff's voice. macOS text-to-speech for key mission events."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn enabled?
  "Voice is enabled unless SECTION_VOICE_ENABLED=false.
   Defaults to true on macOS, false on other platforms."
  []
  (let [env    (System/getenv "SECTION_VOICE_ENABLED")
        on-mac (str/includes? (str (System/getProperty "os.name")) "Mac")]
    (cond
      (= env "false") false
      (= env "true")  true
      :else           on-mac)))

(def voice-name
  "The macOS voice to use. Override with SECTION_VOICE_NAME."
  (or (System/getenv "SECTION_VOICE_NAME") "Samantha"))

;; ---------------------------------------------------------------------------
;; Core speak function
;; ---------------------------------------------------------------------------

(defn say!
  "Speak text asynchronously via the macOS say command. Non-blocking.
   Returns nil immediately; audio plays in the background."
  [text]
  (when (and (seq text) (enabled?))
    (future
      (try
        (p/sh ["say" "-v" voice-name text]
              {:timeout 30000 :err :string :out :string})
        (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; Named event phrases
;; ---------------------------------------------------------------------------

(def ^:private phrases
  {:startup            "Section is online. All systems operational."
   :recovery           "Recovery sequence complete. Standing by."
   :mission-start      (fn [repo number] (str "Mission " number " in " (last (str/split repo #"/")) " is a go."))
   :mission-done       (fn [_repo number] (str "Mission " number " complete. Pull request submitted."))
   :mission-failed     (fn [_repo number] (str "Mission " number " has failed. Requires human review."))
   :mission-no-changes (fn [_repo number] (str "Mission " number " found no changes needed."))
   :housekeeping       "Housekeeping complete."})

(defn speak-event!
  "Speak the phrase for a named event. Extra args are passed to phrase
   functions that accept them (e.g. :mission-start repo number)."
  [event & args]
  (when-let [phrase (get phrases event)]
    (let [text (if (fn? phrase) (apply phrase args) phrase)]
      (say! text))))
