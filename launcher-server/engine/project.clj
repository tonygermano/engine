(defproject org.openintegrationengine/engine "0.1.0-SNAPSHOT"
  :description "A native engine for launching Java applications with advanced .vmoptions support"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-v20.html"}
  ;; Main dependencies
  :dependencies [[org.clojure/clojure "1.12.0"]] ; Keep Clojure 1.12.0
  :main org.openintegrationengine.engine.server.launcher
  :aot :all

  ;; Plugins (keep native-image plugin if needed for actual builds)
  :plugins [[io.taylorwood/lein-native-image "0.3.1"]] ; Consider upgrading this too

  :native-image {:name "engine"}

  :profiles {;; Development profile
             :dev {;; Add :dependencies key if it doesn't exist, or merge into existing one
                   :dependencies [[mockery/mockery "1.0.0"]] ; <-- ADD MOCKERY HERE (check for latest version)
                   :resource-paths ["resources"]}
             ;; Other profiles
             :win {:env {:path-separator ";"}}
             :unix {:env {:path-separator ":"}}})
