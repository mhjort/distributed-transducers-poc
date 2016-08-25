(ns distributed-transducers-poc.rc
  (:require [cheshire.core :refer [generate-string parse-string parse-stream]]
            [clojure.core.async :refer [chan thread go go-loop >! <! <!!]]
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [distributed-transducers-poc.adjacent-queue :as aq]
            [distributed-transducers-poc.sqs :as sqs]
            [serializable.fn :as s])
  (:import [com.amazonaws ClientConfiguration]
           [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
           [com.amazonaws.regions Regions]
           [com.amazonaws.services.lambda.model InvokeRequest]
           [com.amazonaws.services.lambda AWSLambdaClient]))

(defn message-loop [f in-queue out-queue]
  (let [handler (fn [message]
                  (when (= "process" (:type message)
                           (let [result (f (:payload message))]
                             (sqs/send-message out-queue {:index (:index message)
                                                      :payload result}))))
                    (:type message))]
    (go-loop []
             (let [responses (sqs/receive-messages in-queue 5 handler)]
               (when-not (some #(= "stop" %) responses)
                 (recur))))))

(def aws-credentials
  (.getCredentials (DefaultAWSCredentialsProviderChain.)))

(defn parse-result [result]
  (-> result
      (.getPayload)
      (.array)
      (java.io.ByteArrayInputStream.)
      (io/reader)
      (parse-stream true)))

(defn invoke-lambda [payload lambda-function-name region]
  (println "Invoking Lambda")
  (let [client-config (-> (ClientConfiguration.)
                          (.withSocketTimeout (* 6 60 1000)))
        client (-> (AWSLambdaClient. aws-credentials client-config)
                   (.withRegion (Regions/fromName region)))
        request (-> (InvokeRequest.)
                    (.withFunctionName lambda-function-name)
                    (.withPayload (generate-string payload)))]
    (parse-result (.invoke client request))))

(defmacro super-reduce [f xs]
  `(invoke-lambda (pr-str (s/fn [] (reduce ~f ~xs))) "distributed-transducers-poc" "eu-west-1"))

(defn send-ok-messages [c buffer]
  (if-let [result (aq/peek-head buffer)]
    (do
      (go (>! c result))
      (send-ok-messages c (aq/remove-one buffer)))
    buffer))

(defn handler-loop [in-queue]
  (let [continue? (atom true)
        stop-fn #(reset! continue? false)
        ret (chan 10)]
    (go-loop [buffer (aq/create :index)]
             (when @continue?
               (recur (if-let [message (sqs/receive-message in-queue 3)]
                        (send-ok-messages ret (aq/add-one buffer message))
                        buffer))))
    {:results ret
     :stop-fn stop-fn}))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn queue-reduce [f xs in-queues out-queue run-locally?]
  (when run-locally?
    (doseq [in-queue in-queues]
      (message-loop (fn [x] (reduce f x)) in-queue out-queue)))
  (let [{:keys [results stop-fn]} (handler-loop out-queue)
        batches (map (fn [a b] [a b]) (partition-all 4096 xs) (range))]
    (thread
      (doseq [[batch index] batches]
        (sqs/send-message (nth in-queues (mod index (count in-queues)))
                      {:type "process" :index index :payload batch})))
    (let [response (reduce (fn [acc _]
                             (f acc (:payload (<!! results))))
                           (f)
                           batches)]
      (doseq [in-queue in-queues]
        (sqs/send-message in-queue {:type "stop"}))
      (stop-fn)
      response)))

(defn- loadable-namespace [namespace-str]
  (str "/" (-> namespace-str
               (clojure.string/replace #"\." "/")
               (clojure.string/replace #"-" "_"))))

(defn uber-reduce [f f-str xs function-namespace node-count lambda-function-name region]
  (let [out (sqs/create-queue (uuid))
        in-queues  (doall (map (fn [_]
                                 (let [queue (sqs/create-queue (uuid))]
                                   (thread (invoke-lambda {:function f-str
                                                           :function-namespace (loadable-namespace function-namespace)
                                                           :in queue
                                                           :out out}
                                                          lambda-function-name region))
                                   queue))
                               (range node-count)))]
    (let [response (queue-reduce f xs in-queues out false)]
      (Thread/sleep 200)
      (doseq [in-queue in-queues]
        (sqs/delete-queue in-queue))
      (sqs/delete-queue out)
      response)))

(defmacro super-fold [combinef reducef xs node-count]
  `(uber-reduce ~combinef (pr-str (s/fn [ys#] (r/fold ~combinef ~reducef ys#))) ~xs *ns* ~node-count "distributed-transducers-poc" "eu-west-1"))

