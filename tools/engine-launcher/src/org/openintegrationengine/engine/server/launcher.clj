(ns org.openintegrationengine.engine.server.launcher
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io File FileNotFoundException IOException])
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
  "Parses vmoptions lines, returning a map with :options, :classpath, :java-bin-path, and :warnings.
   Takes a config map with functions for side effects/environment info."
  [file-path current-classpath current-java-bin-path config] ; Added current-java-bin-path
  ;; Destructure the functions and values needed from the config map
  (let [{:keys [read-file-fn getenv-fn is-file-fn path-separator]} config]
    (try
      (let [content (read-file-fn file-path)]
        (loop [lines (->> content
                          (str/split-lines)
                          (map str/trim)
                          (remove #(or (str/blank? %) (str/starts-with? % "#"))))
               options []
               classpath current-classpath
               java-bin-path current-java-bin-path
               warnings []]

          (if (empty? lines)
            ;; Return map now includes :java-bin-path
            {:ok? true :options options :classpath classpath :java-bin-path java-bin-path :warnings warnings}

            (let [line (first lines)
                  remaining-lines (rest lines)
                  trimmed-line (str/trim line)
                  ;; Create a substitution function for this scope using the provided getenv-fn
                  subst-fn (fn [s] (substitute-env-vars s getenv-fn))]

              (cond
                ;; --- Handle -include-options ---
                (str/starts-with? trimmed-line "-include-options")
                (let [included-path-str (str/trim (subs trimmed-line (count "-include-options")))]
                  (if (is-file-fn included-path-str)
                    (let [;; Pass the *current* state down
                          sub-result (parse-vmoptions* included-path-str classpath java-bin-path config)]
                      (if (:ok? sub-result)
                        ;; Use the state *returned* from the sub-result
                        (recur remaining-lines
                               (concat options (:options sub-result))
                               (:classpath sub-result)
                               (:java-bin-path sub-result)
                               (concat warnings (:warnings sub-result) [(str "Included options from: " included-path-str)]))
                        ;; Include failed: Recur with warning, keeping current state
                        (recur remaining-lines options classpath java-bin-path
                               (conj warnings (str "Failed to parse included options from '" included-path-str "': " (:error sub-result))))))
                    ;; File Does Not Exist: Recur with warning, keeping current state
                    (recur remaining-lines options classpath java-bin-path
                           (conj warnings (str "Included options path is not a file or not found: '" included-path-str "'")))))

                ;; --- Handle -java-bin ---
                (str/starts-with? trimmed-line "-java-bin ")
                (let [path-from-directive (subst-fn (str/trim (subs trimmed-line (count "-java-bin "))))]
                  ;; Recur, updating java-bin-path. Options and classpath unchanged.
                  (recur remaining-lines
                         options
                         classpath
                         path-from-directive
                         warnings))

                ;; --- Handle -classpath (replace) ---
                (str/starts-with? trimmed-line "-classpath ")
                (recur remaining-lines options (subst-fn (str/trim (subs trimmed-line (count "-classpath ")))) java-bin-path warnings)

                ;; --- Handle -classpath/a (append) ---
                (str/starts-with? trimmed-line "-classpath/a")
                (let [path-to-append (subst-fn (str/trim (subs trimmed-line (count "-classpath/a"))))]
                  (recur remaining-lines options (if (str/blank? classpath) path-to-append (str classpath path-separator path-to-append)) java-bin-path warnings))

                ;; --- Handle -classpath/p (prepend) ---
                (str/starts-with? trimmed-line "-classpath/p")
                (let [path-to-prepend (subst-fn (str/trim (subs trimmed-line (count "-classpath/p"))))]
                  (recur remaining-lines options (if (str/blank? classpath) path-to-prepend (str path-to-prepend path-separator classpath)) java-bin-path warnings))

                ;; --- Handle regular JVM option ---
                :else
                (recur remaining-lines (conj options (subst-fn trimmed-line)) classpath java-bin-path warnings))))))

      (catch FileNotFoundException _
        ;; Return nil for java-bin-path on error
        {:ok? false :error :file-not-found :path file-path :options [] :classpath current-classpath :java-bin-path nil :warnings []}))))

(defn parse-vmoptions
  "Public interface for parsing vmoptions. Takes initial state and config, returns result map. Pure."
  [file-path initial-classpath config]
  (parse-vmoptions* file-path initial-classpath nil config))

(defn determine-java-executable
  "Determines the java executable path based on JAVA_HOME. Pure.
   Takes functions for env lookup and file existence checks."
  [getenv-fn file-exists-fn file-separator]
  (let [java-home (getenv-fn "JAVA_HOME")]
    (if (and java-home (not (str/blank? java-home)))
      (let [exec-path-str (str java-home file-separator "bin" file-separator "java")
            exec-path (io/file exec-path-str) ; Create File object locally
            ]
        (if (file-exists-fn (.getCanonicalPath exec-path)) ; Check canonical path
          (.getCanonicalPath exec-path) 
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
  (let [real-getenv (fn ([var] (System/getenv var)))
        real-read-file slurp
        real-is-file #(.isFile (io/file %))
        real-file-exists #(.exists (io/file %))
        os-file-separator File/separator
        os-path-separator File/pathSeparator

        config {:getenv-fn      real-getenv
                :read-file-fn   real-read-file
                :is-file-fn     real-is-file
                :path-separator os-path-separator}

        vmoptions-file-path "engine.vmoptions"
        process-atom (atom nil) ; For shutdown hook state
        shutting-down?-atom (atom false)

        ;; --- Step 1: Parse vmoptions file ---
        parse-result (if (real-is-file vmoptions-file-path)
                       (parse-vmoptions vmoptions-file-path "" config) ; Initial classpath is empty
                       {:ok? true :options [] :classpath "" :java-bin-path nil :warnings [(str "vmoptions file not found or not a file: " vmoptions-file-path)]})

        ;; Log warnings from parsing
        _ (doseq [warning (:warnings parse-result)] (println "WARNING:" warning))

        ;; Check for fatal parsing errors if needed (optional)
        ;; (when-not (:ok? parse-result) (println "ERROR parsing vmoptions:" (:error parse-result)) (System/exit 1))

        ;; --- Step 2: Determine Final Java Executable Path ---
        java-bin-directive-path (when (:ok? parse-result) ; Only consider if parse was ok
                                  (let [raw-path (get parse-result :java-bin-path)]
                                    (when (and raw-path (not (str/blank? raw-path)))
                                      raw-path))) ; Get non-blank path from directive if present

        final-java-executable (if (and java-bin-directive-path (real-file-exists java-bin-directive-path))
                                ;; Use path from -java-bin directive if it exists
                                (do (println (str "Using Java executable from -java-bin directive: " java-bin-directive-path))
                                    java-bin-directive-path)
                                ;; Otherwise, fall back to standard determination
                                (let [determined-path (determine-java-executable real-getenv real-file-exists os-file-separator)]
                                  (if java-bin-directive-path ; Log if directive was present but invalid
                                    (println (str "WARNING: Path from -java-bin ('" java-bin-directive-path "') not found. Using determined Java executable: " determined-path))
                                    (println (str "Using determined Java executable: " determined-path)))
                                  determined-path))

        ;; --- Step 3: Extract other results and Build Command ---
        vm-options (if (:ok? parse-result) (:options parse-result) []) ; Use options even if parse failed? Or empty list? Defaulting to empty on failure.
        parsed-classpath (if (:ok? parse-result) (:classpath parse-result) "")

        mirth-launcher-jar "mirth-server-launcher.jar"
        final-classpath (if (str/blank? parsed-classpath)
                          mirth-launcher-jar
                          (str mirth-launcher-jar os-path-separator parsed-classpath))
        main-class "com.mirth.connect.server.launcher.MirthLauncher"
        command (build-command-list final-java-executable vm-options final-classpath main-class args)

        ;; --- Side Effects Execution ---
        _ (println "Launching Engine with command:" (str/join " " command)) ; Side effect: Logging
        
        ;; Shutdown Hook (Side effect)
        shutdown-hook (Thread. (fn []
                                 (reset! shutting-down?-atom true)
                                 (when-let [proc @process-atom]
                                   (println "\nLauncher shutting down, attempting to terminate Engine process...")
                                   (try
                                     (.destroy proc)
                                     (catch Exception e (println "ERROR during shutdown hook:" (.getMessage e)))))))
        _ (.addShutdownHook (Runtime/getRuntime) shutdown-hook)

        exit-code (try ; Process Launching and Management (Side effect)
                    (let [process (.start (.inheritIO (ProcessBuilder. ^java.util.List command)))]
                      (reset! process-atom process) ; Store process for hook
                      (.waitFor process)) ; Return exit code
                    (catch IOException e
                      (println (str "ERROR: Could not start Engine process: " (.getMessage e)))
                      (println "Check java executable, JAR path, and command details:")
                      (println (str/join " " command))
                      1) ; Return error code 1
                    (finally
                      (reset! process-atom nil) ; Clear process atom
                      (when (not @shutting-down?-atom)
                        (try
                          (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
                          (catch IllegalStateException _)
                          )))) ; Remove hook
        ]

    ;; Exit with final code (Side effect)
    (System/exit exit-code)))
