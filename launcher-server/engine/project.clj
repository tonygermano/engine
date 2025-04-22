(defproject org.openintegrationengine/engine "0.1.0-SNAPSHOT"
  :description "A native engine for launching Java applications with advanced .vmoptions support"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-v20.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :main org.openintegrationengine.engine.server.launcher
  :aot :all

  ;; Add the plugin dependency
  :plugins [[io.taylorwood/lein-native-image "0.3.1"]]

  ;; Configuration for the native-image plugin
  ;; The :name key here matches the :native-image map you had,
  ;; but it's interpreted by the plugin.
  :native-image {:name "engine"
                ;; Optional: Add GraalVM arguments if needed
                ;; :opts ["--no-fallback" "--verbose"]
                }

  :profiles {:dev {:resource-paths ["resources"]}})
