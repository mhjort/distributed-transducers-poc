(ns distributed-transducers-poc.core
  (:require [uswitch.lambada.core :refer [deflambdafn]]
            [clojure.core.async :refer [chan thread go go-loop >! <! <!!]]
            [clojure.java.io :as io]
            [distributed-transducers-poc.sqs :as sqs]
            [cheshire.core :refer [generate-stream parse-stream]]))

(defn message-loop [f in-queue out-queue]
  (println "Msg loop with" f in-queue out-queue)
  (let [handler (fn [message]
                  (when (= "process" (:type message)
                           (let [result (f (:payload message))]
                             (sqs/send-message out-queue {:index (:index message)
                                                          :payload result}))))
                  (:type message))]
    (loop []
      (let [responses (sqs/receive-messages in-queue 5 handler)]
        (when-not (some #(= "stop" %) responses)
          (recur))))))

(deflambdafn distributed-transducers-poc.LambdaFn
  [in out ctx]
  (load "/serializable/fn")
  (load "/clojure/core/reducers")
  (let [input (parse-stream (io/reader in) true)
        output (io/writer out)]
    (println "Called with:" input)
    (load (:function-namespace input))
    (message-loop (load-string (:function input))
                  (:in input)
                  (:out input))
    (println "Run successfully")
    (generate-stream {:success true} output)
    (.flush output)))

