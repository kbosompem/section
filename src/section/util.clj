(ns section.util
  "Shared utilities."
  (:require [babashka.fs :as fs]))

(defn atomic-spit
  "Write content to a file atomically via temp-file + rename.
   Prevents readers from seeing a half-written file."
  [path content]
  (let [target (fs/path path)
        parent (fs/parent target)
        tmp    (fs/create-temp-file {:dir (str parent)
                                      :prefix ".section-tmp-"
                                      :suffix ".edn"})]
    (try
      (spit (str tmp) content)
      (fs/move tmp target {:replace-existing true
                            :atomic-move true})
      (catch Exception e
        (fs/delete-if-exists tmp)
        (throw e)))))
