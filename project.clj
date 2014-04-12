(defproject geschichte "0.1.0-SNAPSHOT"
  :description "A distributed version control system with global synching."
  :url "http://github.com/ghubber/geschichte"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/cljs" "src/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2156"]
                 [org.clojure/core.incubator "0.1.3"]
;                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 ;; hand-pull upstream bugfix for now
                 [org.clojure/core.async "0.1.0-SNAPSHOT"]
                 [http-kit "2.1.18"]
                 [http.async.client "0.5.2"]
                 [hasch "0.1.0-SNAPSHOT"]
                 [konserve "0.1.0-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]}}
  :plugins [[lein-cljsbuild "1.0.1"]
            [com.keminglabs/cljx "0.3.2"]
            [com.cemerick/austin "0.1.3"]]

  :cljx {:builds [{:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :clj}

                  {:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :cljs}]}

  :hooks [cljx.hooks]

  :cljsbuild
  {:builds
   [{:source-paths ["src/cljs"
                    "target/classes"]
     :compiler
     {:output-to "resources/public/js/main.js"
      :optimizations :simple
      :pretty-print true}}]}

  :documentation
  {:files {"index"
           {:input "test/doc/intro.clj"
            :title "geschichte repository API"
            :sub-title "An introduction to the Repository functionality."
            :author "christian weilbach"
            :email  "ch_weil polyc0l0r net"}
           "synching"
           {:input "test/doc/sync.clj"
            :title "geschichte synching API"
            :sub-title "An introduction to the Synching functionality."
            :author "christian weilbach"
            :email  "ch_weil polyc0l0r net"}}})
