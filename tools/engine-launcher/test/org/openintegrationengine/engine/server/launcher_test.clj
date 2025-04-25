(ns org.openintegrationengine.engine.server.launcher-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            ;; Require the namespace under test, aliased for clarity
            [org.openintegrationengine.engine.server.launcher :as launcher])
  (:import [java.io File FileNotFoundException])) ; Import for mocking file reads

;; NOTE: No global fixture (env-and-file-fixture) is needed anymore.
;; NOTE: No create-temp-vmoptions helper is needed anymore.

;; === Tests for substitute-env-vars ===

(deftest substitute-env-vars-test
  ;; Define a mock environment for these tests
  (let [mock-env {"EXISTING_VAR" "VAR_VALUE"
                  "OTHER_VAR"    "OTHER_VALUE"}
        mock-getenv (fn [var-name] (get mock-env var-name))]

    (testing "String with no variables"
      (is (= "hello world" (launcher/substitute-env-vars "hello world" mock-getenv))))

    (testing "String with existing variable"
      (is (= "hello VAR_VALUE" (launcher/substitute-env-vars "hello ${EXISTING_VAR}" mock-getenv))))

    (testing "String with multiple existing variables"
      (is (= "VAR_VALUE meets OTHER_VALUE" (launcher/substitute-env-vars "${EXISTING_VAR} meets ${OTHER_VAR}" mock-getenv))))

    (testing "String with non-existent variable"
      ;; substitute-env-vars replaces non-found with ""
      (is (= "hello " (launcher/substitute-env-vars "hello ${NON_EXISTENT_VAR}" mock-getenv))))

    (testing "String with mixed variables"
      (is (= "VAR_VALUE and " (launcher/substitute-env-vars "${EXISTING_VAR} and ${NON_EXISTENT_VAR}" mock-getenv))))

    (testing "Empty string"
      (is (= "" (launcher/substitute-env-vars "" mock-getenv))))))

;; === Tests for parse-vmoptions ===

(deftest parse-vmoptions-test
  ;; Define mock file contents and environment for these tests
  (let [mock-files {"main.vmoptions" (str "-Xmx512m\n"
                                          "-Dprop=${ENV_PROP}\n"
                                          "-java-cmd /specific/java\n"
                                          "-include-options included.vmoptions\n"
                                          "-classpath/a /main/append")

                    "included.vmoptions" (str "# A comment\n"
                                              "-XincOpt\n"
                                              "-java-cmd /included/java\n"
                                              "-classpath/p /included/prepend")

                    "override.vmoptions" (str "-include-options included.vmoptions\n" ; Include sets it first
                                              "-java-cmd /override/java") ; Then override

                    "env_java.vmoptions" "-java-cmd ${JAVA_CMD_PATH}"
                    "cp_replace.vmoptions" "-classpath /new/path"
                    "cp_append.vmoptions" "-classpath/a /append"
                    "cp_prepend.vmoptions" "-classpath/p /prepend"
                    "empty.vmoptions" ""
                    "only_comments.vmoptions" "# line 1\n   # line 2"}

        mock-env {"ENV_PROP" "env-value"
                  "JAVA_CMD_PATH" "/env/java/path"}

        ;; Mock implementations for config map
        mock-read-file (fn [path]
                         (if-let [content (get mock-files path)]
                           content
                           (throw (FileNotFoundException. (str path " not found in mock files")))))
        mock-getenv (fn [var-name] (get mock-env var-name))
        mock-is-file (fn [path] (contains? mock-files path))

        ;; Basic config map using mocks (Unix path separator for tests)
        test-config {:read-file-fn   mock-read-file
                     :getenv-fn      mock-getenv
                     :is-file-fn     mock-is-file
                     :path-separator ":"}]

    (testing "Parsing basic file with -java-cmd, includes and substitutions"
      (let [result (launcher/parse-vmoptions "main.vmoptions" "initial/cp" test-config)]
        (is (:ok? result))
        (is (= ["-Xmx512m" "-Dprop=env-value" "-XincOpt"] (:options result)))
        (is (= "/included/prepend:initial/cp:/main/append" (:classpath result)))
        (is (= "/included/java" (:java-cmd-path result)))
        (is (= 1 (count (:warnings result))))
        (is (str/includes? (first (:warnings result)) "Included options from: included.vmoptions"))))

    (testing "Parsing file where override options included -java-cmd"
      (let [result (launcher/parse-vmoptions "override.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (= ["-XincOpt"] (:options result)))
        (is (= "/included/prepend" (:classpath result)))
        (is (= "/override/java" (:java-cmd-path result)))
        (is (= 1 (count (:warnings result))))))

    (testing "Parsing file with -java-cmd using env var"
      (let [result (launcher/parse-vmoptions "env_java.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (empty? (:options result)))
        (is (= "" (:classpath result)))
        (is (= "/env/java/path" (:java-cmd-path result))) ; Path from env var substitution
        (is (empty? (:warnings result)))))

    (testing "Empty file"
      (let [result (launcher/parse-vmoptions "empty.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (empty? (:options result)))
        (is (= "" (:classpath result)))
        (is (empty? (:warnings result)))))

    (testing "File with only comments/blanks"
      (let [result (launcher/parse-vmoptions "only_comments.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (empty? (:options result)))
        (is (= "" (:classpath result)))
        (is (empty? (:warnings result)))))

    (testing "Classpath replace"
      (let [result (launcher/parse-vmoptions "cp_replace.vmoptions" "old/path" test-config)]
        (is (:ok? result))
        (is (empty? (:options result)))
        (is (= "/new/path" (:classpath result)))))

    (testing "Classpath append"
      (let [result (launcher/parse-vmoptions "cp_append.vmoptions" "initial" test-config)]
        (is (:ok? result))
        (is (= "initial:/append" (:classpath result)))))

    (testing "Classpath append to empty"
      (let [result (launcher/parse-vmoptions "cp_append.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (= "/append" (:classpath result)))))

    (testing "Classpath prepend"
      (let [result (launcher/parse-vmoptions "cp_prepend.vmoptions" "initial" test-config)]
        (is (:ok? result))
        (is (= "/prepend:initial" (:classpath result)))))

    (testing "Classpath prepend to empty"
      (let [result (launcher/parse-vmoptions "cp_prepend.vmoptions" "" test-config)]
        (is (:ok? result))
        (is (= "/prepend" (:classpath result)))))

    (testing "Included file check (file not found)"
      (let [config-no-include (assoc test-config :is-file-fn (constantly false))
            result (launcher/parse-vmoptions "main.vmoptions" "" config-no-include)]
        ;; Should parse main options, but skip include and add warning
        (is (:ok? result))
        (is (= ["-Xmx512m" "-Dprop=env-value"] (:options result)))
        (is (= "/main/append" (:classpath result))) ; Initial "" + main append
        (is (= 1 (count (:warnings result))))
        (is (str/includes? (first (:warnings result)) "not a file or not found: 'included.vmoptions'"))))

    (testing "Main file not found"
      (let [result (launcher/parse-vmoptions "/non/existent/path.vmoptions" "initial/cp" test-config)]
        (is (false? (:ok? result)))
        (is (nil? (:java-cmd-path result)))
        (is (= :file-not-found (:error result)))
        (is (= "/non/existent/path.vmoptions" (:path result)))
        (is (= "initial/cp" (:classpath result))) ; Returns initial classpath
        (is (empty? (:options result)))))))

;; === Tests for determine-java-executable ===

(deftest determine-java-executable-test
  (testing "JAVA_HOME set and java exists"
    (let [mock-env {"JAVA_HOME" "/opt/java"}
          mock-exists #(= % "/opt/java/bin/java")] ; Only this path exists
      (is (= "/opt/java/bin/java"
             (launcher/determine-java-executable (fn [v] (get mock-env v)) mock-exists File/separator)))))

  (testing "JAVA_HOME set but java does NOT exist"
    (let [mock-env {"JAVA_HOME" "/opt/java"}
          mock-exists (constantly false)] ; Nothing exists
      (is (= "java" ; Should fallback
             (launcher/determine-java-executable (fn [v] (get mock-env v)) mock-exists File/separator)))))

  (testing "JAVA_HOME not set"
    (let [mock-env {}
          mock-exists (constantly true)] ; Assume "java" exists on path
      (is (= "java" ; Should use default
             (launcher/determine-java-executable (fn [v] (get mock-env v)) mock-exists File/separator))))))

;; === Tests for build-command-list ===

(deftest build-command-list-test
  (testing "Basic command construction"
    (is (= ["java" "-Xmx1g" "-cp" "app.jar:/lib/*" "com.app.Main" "arg1"]
           (launcher/build-command-list "java"
                                        ["-Xmx1g"]
                                        "app.jar:/lib/*"
                                        "com.app.Main"
                                        ["arg1"]))))

  (testing "No VM options"
    (is (= ["java" "-cp" "app.jar" "com.app.Main"]
           (launcher/build-command-list "java"
                                        [] ; Empty vm options
                                        "app.jar"
                                        "com.app.Main"
                                        [])))) ; Empty args

  (testing "No pass-through arguments"
    (is (= ["java" "-Dprop=val" "-cp" "app.jar" "com.app.Main"]
           (launcher/build-command-list "java"
                                        ["-Dprop=val"]
                                        "app.jar"
                                        "com.app.Main"
                                        [])))) ; Empty args

  (testing "Multiple VM options and arguments"
    (is (= ["java" "-Xmx1g" "-Dprop=val" "-cp" "app.jar" "com.app.Main" "arg1" "--flag"]
           (launcher/build-command-list "java"
                                        ["-Xmx1g" "-Dprop=val"]
                                        "app.jar"
                                        "com.app.Main"
                                        ["arg1" "--flag"])))))

;; NOTE: No unit test for -main as its primary role is orchestrating
;;       side effects using the now-testable pure helper functions.
;;       -main should be tested via integration tests if needed.
