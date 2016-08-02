(defproject distributed-transducers-poc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [uswitch/lambada "0.1.2"]]
  :lambda {"demo" [{:handler "distributed-transducers-poc.LambdaFn"
                    :memory-size 1536
                    :timeout 300
                    :function-name "distributed-transducers-poc"
                    :region "eu-west-1"
                    :s3 {:bucket "distributed-transducers-poc"
                         :object-key "lambda.jar"}}]}
  :plugins [[lein-clj-lambda "0.4.1"]]
  :aot [distributed-transducers-poc.core])

