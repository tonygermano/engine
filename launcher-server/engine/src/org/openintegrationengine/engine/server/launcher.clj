(ns org.openintegrationengine.engine.server.launcher
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]
           [java.io File FileNotFoundException IOException])
  (:gen-class))

;; =============================================
;; Helper Functions
;; =============================================

(defn substitute-env-vars
  "Substitutes ${VAR_NAME} patterns in a string using a provided getter function. Pure."
  [s getenv-fn] ; Takes a function to resolve env vars
  (str/replace s #"\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}"
               (fn [[_ var-name]] (or (getenv-fn var-name) ""))))

(defn- parse-vmoptions* ; Internal recursive helper
  "Parses vmoptions lines, returning a map with :options, :classpath, and :warnings.
   Takes a config map with functions for side effects/environment info."
  [file-path current-classpath config]
  ;; Destructure the functions and values needed from the config map
  (let [{:keys [read-file-fn getenv-fn is-file-fn path-separator]} config]
    ;; Use try/catch for the initial file read of the current file-path
    (try
      ;; Read the file content using the provided function
      (let [content (read-file-fn file-path)]
        ;; Start the loop processing lines
        (loop [lines (->> content
                          (str/split-lines)
                          (map str/trim)
                          ;; Remove empty lines and comments
                          (remove #(or (str/blank? %) (str/starts-with? % "#"))))
               options [] ; Accumulator for JVM options
               classpath current-classpath ; Accumulator for the classpath string
               warnings []] ; Accumulator for any warnings generated

          ;; Base case: No more lines to process
          (if (empty? lines)
            {:ok? true :options options :classpath classpath :warnings warnings} ; Return success map

            ;; Recursive step: Process the first line
            (let [line (first lines)
                  remaining-lines (rest lines)
                  trimmed-line (str/trim line)
                  ;; Create a substitution function for this scope using the provided getenv-fn
                  subst-fn (fn [s] (substitute-env-vars s getenv-fn))]

              ;; Dispatch based on the start of the line
              (cond
                ;; --- Handle -include-options ---
                (str/starts-with? trimmed-line "-include-options")
                (let [included-path-str (str/trim (subs trimmed-line (count "-include-options")))]
                  (if (is-file-fn included-path-str) ; Use provided function to check file
                    ;; --- File Exists ---
                    (let [;; Recursively call self to parse the included file.
                          ;; Pass the *current* loop's classpath state.
                          sub-result (parse-vmoptions* included-path-str classpath config)]
                      (if (:ok? sub-result)
                        ;; Include successful: Recur with combined options, the *new* classpath
                        ;; from the sub-result, and accumulated warnings.
                        (recur remaining-lines
                               (concat options (:options sub-result))
                               (:classpath sub-result) ; Use updated classpath
                               (concat warnings (:warnings sub-result) [(str "Included options from: " included-path-str)]))
                        ;; Include failed (e.g., nested file not found): Recur with warning,
                        ;; keeping the *current* options and classpath state.
                        (recur remaining-lines
                               options
                               classpath ; Keep current classpath
                               (conj warnings (str "Failed to parse included options from '" included-path-str "': " (:error sub-result))))))
                    ;; --- File Does Not Exist ---
                    ;; Recur with warning, keeping current options and classpath.
                    (recur remaining-lines
                           options
                           classpath
                           (conj warnings (str "Included options path is not a file or not found: '" included-path-str "'")))))

                ;; --- Handle -classpath (replace) ---
                (str/starts-with? trimmed-line "-classpath ")
                (recur remaining-lines
                       options ; Options unchanged
                       (subst-fn (str/trim (subs trimmed-line (count "-classpath ")))) ; Replace classpath
                       warnings)

                ;; --- Handle -classpath/a (append) ---
                (str/starts-with? trimmed-line "-classpath/a")
                (let [path-to-append (subst-fn (str/trim (subs trimmed-line (count "-classpath/a"))))]
                  (recur remaining-lines
                         options
                         (if (str/blank? classpath) ; Append using configured separator
                           path-to-append
                           (str classpath path-separator path-to-append))
                         warnings))

                ;; --- Handle -classpath/p (prepend) ---
                (str/starts-with? trimmed-line "-classpath/p")
                (let [path-to-prepend (subst-fn (str/trim (subs trimmed-line (count "-classpath/p"))))]
                  (recur remaining-lines
                         options
                         (if (str/blank? classpath) ; Prepend using configured separator
                           path-to-prepend
                           (str path-to-prepend path-separator classpath))
                         warnings))

                ;; --- Handle regular JVM option ---
                :else
                (recur remaining-lines
                       (conj options (subst-fn trimmed-line)) ; Add substituted option
                       classpath ; Classpath unchanged
                       warnings)))))) ; End let and loop body

      ;; Catch file not found for the *current* file-path being processed
      (catch FileNotFoundException _
        {:ok? false :error :file-not-found :path file-path :options [] :classpath current-classpath :warnings []}))))



(defn parse-vmoptions
  "Public interface for parsing vmoptions. Takes initial state and config, returns result map. Pure."
  [file-path initial-classpath config]
  ;; Doesn't do much now, but could validate config or handle top-level errors
  (parse-vmoptions* file-path initial-classpath config))

(defn determine-java-executable
  "Determines the java executable path based on JAVA_HOME. Pure.
   Takes functions for env lookup and file existence checks."
  [getenv-fn file-exists-fn file-separator]
  (let [java-home (getenv-fn "JAVA_HOME")]
    (if (and java-home (not (str/blank? java-home)))
      (let [exec-path-str (str java-home file-separator "bin" file-separator "java")
            exec-path (io/file exec-path-str) ; Create File object locally
            ]
        (if (file-exists-fn (.getAbsolutePath exec-path)) ; Check absolute path
          (.getAbsolutePath exec-path)
          ;; Log warning via side-effect fn if passed, or just return fallback
          "java")) ; Simplified: return fallback directly
      "java")))

(defn build-command-list
  "Constructs the final command vector. Pure."
  [java-exec vm-opts final-cp main-cls args]
  (-> [java-exec]
      (into vm-opts)
      (into ["-cp" final-cp])
      (conj main-cls)
      (into args)))

;; =============================================
;; Main Function (Orchestrates Side Effects)
;; =============================================

(defn -main [& args]
  ;; --- Define Real Side-Effecting Functions/Values ---
  (let [;; Environment Access
        real-getenv (fn ([var] (System/getenv var)))
        ;; File System Access
        real-read-file slurp
        real-is-file #(.isFile (io/file %))
        real-file-exists #(.exists (io/file %))
        ;; OS-specific separators
        os-file-separator File/separator ; e.g., "/" or "\"
        os-path-separator File/pathSeparator ; e.g., ":" or ";"

        ;; --- Configuration for Pure Functions ---
        config {:getenv-fn      real-getenv
                :read-file-fn   real-read-file
                :is-file-fn     real-is-file
                :path-separator os-path-separator
                ;; No log-fn passed, parse-vmoptions* won't log warnings internally now
                }

        ;; --- Core Logic using Pure(r) Functions ---
        vmoptions-file-path "engine.vmoptions" ; Define path
        process-atom (atom nil) ; For shutdown hook state

        ;; Determine Java executable
        java-executable (determine-java-executable real-getenv real-file-exists os-file-separator)
        _ (println (str "Using Java executable: " java-executable)) ; Side effect: Logging

        ;; Parse vmoptions file
        parse-result (if (real-is-file vmoptions-file-path) ; Check existence before parsing
                       (parse-vmoptions vmoptions-file-path "" config)
                       {:ok? true :options [] :classpath "" :warnings [(str "vmoptions file not found or not a file: " vmoptions-file-path)]})

        ;; Log warnings from parsing (Side effect)
        _ (doseq [warning (:warnings parse-result)] (println "WARNING:" warning))
        ;; Could add error handling here if parse-result wasn't :ok?

        ;; Extract results (assuming :ok? or using defaults)
        vm-options (:options parse-result [])
        parsed-classpath (:classpath parse-result "")

        ;; Construct Classpath and Command (Pure)
        mirth-launcher-jar "mirth-server-launcher.jar"
        final-classpath (if (str/blank? parsed-classpath)
                          mirth-launcher-jar
                          (str mirth-launcher-jar os-path-separator parsed-classpath))
        main-class "com.mirth.connect.server.launcher.MirthLauncher"
        command (build-command-list java-executable vm-options final-classpath main-class args)

        ;; --- Side Effects Execution ---
        _ (println "Launching Engine with command:" (str/join " " command)) ; Side effect: Logging

        ;; Shutdown Hook (Side effect)
        shutdown-hook (Thread. (fn []
                                 (when-let [proc @process-atom]
                                   (println "\nLauncher shutting down, attempting to terminate Engine process...")
                                   (try
                                     (.destroy proc)
                                     (when-not (.waitFor proc 5 TimeUnit/SECONDS)
                                       (println "Engine process did not terminate gracefully, forcing shutdown...")
                                       (.destroyForcibly proc))
                                     (println "Engine process termination signal sent.")
                                     (catch Exception e (println "ERROR during shutdown hook:" (.getMessage e)))))))
        _ (.addShutdownHook (Runtime/getRuntime) shutdown-hook)

        exit-code (try ; Process Launching and Management (Side effect)
                    (let [process (.start (ProcessBuilder. ^java.util.List command))]
                      (reset! process-atom process) ; Store process for hook
                      (with-open [input-stream (.getInputStream process) error-stream (.getErrorStream process)]
                        (let [out-thread (future (io/copy input-stream System/out))
                              err-thread (future (io/copy error-stream System/err))
                              ec (.waitFor process)]
                          @out-thread @err-thread ; Ensure streams are flushed
                          ec))) ; Return exit code
                    (catch IOException e
                      (println (str "ERROR: Could not start Engine process: " (.getMessage e)))
                      (println "Check java executable, JAR path, and command details:")
                      (println (str/join " " command))
                      1) ; Return error code 1
                    (finally
                      (reset! process-atom nil) ; Clear process atom
                      (.removeShutdownHook (Runtime/getRuntime) shutdown-hook))) ; Remove hook
        ]

    ;; Exit with final code (Side effect)
    (System/exit exit-code)))
