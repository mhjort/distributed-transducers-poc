(ns distributed-transducers-poc.core
  (:require [uswitch.lambada.core :refer [deflambdafn]]
            [clojure.core.async :refer [chan thread go go-loop >! <! <!!]]
            [clojure.java.io :as io]
            [distributed-transducers-poc.sqs :as sqs]
            [cheshire.core :refer [generate-stream parse-stream]]))

(defn message-loop [f in-queue out-queue]
  (println "Msg loop with" f in-queue out-queue)
  (let [handler (fn [message]
                  (println "handling" message "with function" f)
                  (when (= "process" (:type message)
                           (let [result (f (:payload message))]
                             (sqs/send-message out-queue {:index (:index message)
                                                          :payload result}))))
                  (:type message))]
    (loop []
      (let [responses (sqs/receive-messages in-queue 5 handler)]
        (println "got responses" responses)
        (when-not (some #(= "stop" %) responses)
          (recur))))))

(deflambdafn distributed-transducers-poc.LambdaFn
  [in out ctx]
  (load "/serializable/fn")
  (let [input (parse-stream (io/reader in) true)
        output (io/writer out)
        result (message-loop (load-string (:function input))
                             (:in input)
                             (:out input))]
    (println "Returning result" result)
    (generate-stream result output)
    (.flush output)))

