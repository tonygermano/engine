(ns org.openintegrationengine.engine.server.launcher
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  ;; Import TimeUnit for the optional timed waitFor in the shutdown hook
  (:import [java.util.concurrent TimeUnit])
  (:gen-class))

;; Define the path separator based on environment or default to Unix style
(def ^:private path-separator (or (System/getenv "path-separator") ":"))

;; Function to substitute environment variables like ${VAR_NAME}
(defn- substitute-env-vars [s]
  (str/replace s #"\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}"
               (fn [[_ var-name]] (or (System/getenv var-name) "")))) ; Return "" for undefined vars

;; Function to parse the .vmoptions file, handling includes and classpath directives
(defn- parse-vmoptions [file-path classpath]
  (try
    (loop [lines (->> (slurp file-path)
                      (str/split-lines)
                      (map str/trim)
                      (remove #(or (str/blank? %) (str/starts-with? % "#"))))
           options []
           current-classpath classpath]
      (if (empty? lines)
        [options current-classpath]
        (let [line (first lines)
              trimmed-line (str/trim line)]
          (cond
            (str/starts-with? trimmed-line "-include-options")
            (let [included-path-str (str/trim (subs trimmed-line (count "-include-options")))
                  included-file (io/file included-path-str)]
              (if (.isFile included-file) ; Check if it's a valid file
                (let [[included-options new-classpath] (parse-vmoptions (.getAbsolutePath included-file) current-classpath)]
                  (recur (rest lines) (concat options included-options) new-classpath))
                (do (println (str "Warning: Included options path is not a file or not found: '" included-path-str "'"))
                    (recur (rest lines) options current-classpath)))) ; Skip if not a file


            (str/starts-with? trimmed-line "-classpath ") ; Note the space - prevents matching -classpath/a etc.
            (recur (rest lines) options (substitute-env-vars (str/trim (subs trimmed-line (count "-classpath ")))))

            (str/starts-with? trimmed-line "-classpath/a")
            (let [path-to-append (substitute-env-vars (str/trim (subs trimmed-line (count "-classpath/a"))))]
               (recur (rest lines) options (if (str/blank? current-classpath)
                                             path-to-append
                                             (str current-classpath path-separator path-to-append))))

            (str/starts-with? trimmed-line "-classpath/p")
             (let [path-to-prepend (substitute-env-vars (str/trim (subs trimmed-line (count "-classpath/p"))))]
               (recur (rest lines) options (if (str/blank? current-classpath)
                                             path-to-prepend
                                             (str path-to-prepend path-separator current-classpath))))

            :else
            (recur (rest lines) (conj options (substitute-env-vars trimmed-line)) current-classpath)))))
    (catch java.io.FileNotFoundException _
      ;; Don't print warning here if the main file isn't found, handle in -main
      [[] classpath])))

;; === Main Application Entry Point ===
(defn -main [& args]
  (let [;; --- Configuration Setup ---
        vmoptions-file (io/file "." "engine.vmoptions")
        ;; Atom to hold the running child process reference for the shutdown hook
        process-atom (atom nil)
        java-home (System/getenv "JAVA_HOME") ; Get JAVA_HOME (can be nil)

        ;; Determine java executable path
        java-executable (if (and java-home (not (str/blank? java-home)))
                          (let [exec-path (io/file java-home "bin" "java")]
                            (if (.isFile exec-path) ; Check if java exists at path
                              (.getAbsolutePath exec-path)
                              (do (println (str "WARNING: java executable not found in specified JAVA_HOME: '" (.getAbsolutePath exec-path) "'. Falling back to 'java'."))
                                  "java"))) ; Fallback if not found in JAVA_HOME
                          "java") ; Default if JAVA_HOME is not set or blank
        _ (println (str "Using Java executable: " java-executable))

        ;; Parse vmoptions file if it exists
        [vm-options parsed-classpath] (if (.isFile vmoptions-file)
                                         (parse-vmoptions (.getAbsolutePath vmoptions-file) "")
                                         (do (println "INFO: engine.vmoptions not found in current directory. Proceeding without custom VM options.")
                                             [[] ""])) ; Default if file doesn't exist or isn't a file

        ;; --- Classpath and Command Construction ---
        mirth-launcher-jar "mirth-server-launcher.jar" ; Target application JAR
        final-classpath (if (str/blank? parsed-classpath)
                          mirth-launcher-jar
                          (str mirth-launcher-jar path-separator parsed-classpath))
        main-class "com.mirth.connect.server.launcher.MirthLauncher" ; Target application main class

        ;; Build the full command list
        command (-> [java-executable] ; Start with java executable
                    (into vm-options) ; Add parsed JVM options
                    (into ["-cp" final-classpath]) ; Add classpath flag and value
                    (conj main-class) ; Add main class to run
                    (into args))] ; Add any pass-through arguments given to the launcher

    ;; --- Shutdown Hook Setup ---
    ;; Add a hook to attempt graceful, then forceful, shutdown of the child process
    ;; if the launcher itself is terminated (e.g., via Ctrl+C).
    (.addShutdownHook (Runtime/getRuntime)
      (Thread.
       (fn []
         ;; This code runs when the launcher's JVM is shutting down
         (when-let [proc @process-atom] ; Check if process atom holds a valid process reference
           (println "\nLauncher shutting down, attempting to terminate Mirth process...")
           (try
             (.destroy proc) ; Attempt graceful shutdown (sends SIGTERM on Unix/Linux)
             ;; Optional: Wait briefly and force kill if needed
             (when-not (.waitFor proc 5 TimeUnit/SECONDS) ; Wait up to 5 seconds
               (println "Mirth process did not terminate gracefully after 5s, forcing shutdown...")
               (.destroyForcibly proc)) ; Force kill (sends SIGKILL on Unix/Linux)
             (println "Mirth process termination signal sent.")
             (catch Exception e
               ;; Catch potential errors during shutdown hook execution
               (println (str "ERROR during shutdown hook: " (.getMessage e)))))))))
    ;; --- End Shutdown Hook Setup ---

    (println "Launching Mirth with command:" (str/join " " command))
    (try
      ;; --- Process Execution ---
      ;; Start the child process
      (let [process (.start (ProcessBuilder. ^java.util.List command))]
        ;; Store the running process reference in the atom for the shutdown hook
        (reset! process-atom process)

        ;; Handle child process output/error streams asynchronously to prevent blocking
        (with-open [input-stream (.getInputStream process)
                    error-stream (.getErrorStream process)]
          ;; Start threads to copy child streams to launcher's streams
          (let [out-thread (future (io/copy input-stream System/out))
                err-thread (future (io/copy error-stream System/err))]

            ;; Wait for the child process to complete its execution naturally
            (let [exit-code (.waitFor process)]
              ;; Ensure stream copying is finished before exiting launcher
              @out-thread
              @err-thread
              (println (str "\nMirth process exited with code: " exit-code))
              ;; Exit the launcher with the same exit code as the child process
              (System/exit exit-code)))))

    ;; --- Error Handling ---
    (catch java.io.IOException e
      ;; Handle errors during process startup (e.g., java command not found)
      (println (str "ERROR: Could not start Mirth process: " (.getMessage e)))
      (println "Check:")
      (println (str " - If '" java-executable "' is correct and exists."))
      (println (str " - If '" mirth-launcher-jar "' is present."))
      (println (str " - If the generated command is valid: " (str/join " " command)))
      (System/exit 1)) ; Exit launcher with an error code

    (finally
      ;; --- Cleanup ---
      ;; Clear the process atom when -main finishes (either normally or via exception).
      ;; This prevents the shutdown hook from attempting to act on a process
      ;; that has already finished or failed to start.
      (reset! process-atom nil)))))
