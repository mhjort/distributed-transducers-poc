(ns distributed-transducers-poc.core
  (:require [uswitch.lambada.core :refer [deflambdafn]]
            [clojure.java.io :as io]
            [cheshire.core :refer [generate-stream parse-stream]]))

(deflambdafn distributed-transducers-poc.LambdaFn
  [in out ctx]
  (println "OMG I'm running in the cloud!!!111oneone" in)
  (load "/serializable/fn")
  (let [input (parse-stream (io/reader in) true)
        output (io/writer out)
        result ((load-string (:function input)))]
    (println "Returning result" result)
    (generate-stream result output)
    (.flush output)))

