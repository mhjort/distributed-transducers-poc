(ns distributed-transducers-poc.rc
  (:require [cheshire.core :refer [generate-string parse-string parse-stream]]
            [clojure.core.async :refer [chan thread go go-loop >! <! <!!]]
            [clojure.java.io :as io]
            [distributed-transducers-poc.adjacent-queue :as aq]
            [serializable.fn :as s])
  (:import [com.amazonaws ClientConfiguration]
           [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
           [com.amazonaws.regions Regions]
           [com.amazonaws.services.sqs AmazonSQSClient]
           [com.amazonaws.services.sqs.model ReceiveMessageRequest]
           [com.amazonaws.services.lambda.model InvokeRequest]
           [com.amazonaws.services.lambda AWSLambdaClient]))

(def aws-credentials
  (.getCredentials (DefaultAWSCredentialsProviderChain.)))

(defn parse-result [result]
  (-> result
      (.getPayload)
      (.array)
      (java.io.ByteArrayInputStream.)
      (io/reader)
      (parse-stream true)))

(defn invoke-lambda [f lambda-function-name region]
  (println "Invoking Lambda")
  (let [client-config (-> (ClientConfiguration.)
                          (.withSocketTimeout (* 6 60 1000)))
        client (-> (AWSLambdaClient. aws-credentials client-config)
                   (.withRegion (Regions/fromName region)))
        request (-> (InvokeRequest.)
                    (.withFunctionName lambda-function-name)
                    (.withPayload (generate-string {:function f})))]
    (parse-result (.invoke client request))))

(defmacro super-reduce [f xs]
  `(invoke-lambda (pr-str (s/fn [] (reduce ~f ~xs))) "distributed-transducers-poc" "eu-west-1"))

(def sqs-client (AmazonSQSClient. aws-credentials))

(defn create-queue [queue-name]
  (let [queue-url (-> sqs-client
                      (.createQueue queue-name)
                      (.getQueueUrl))]
    (loop []
      (let [urls (.getQueueUrls (.listQueues sqs-client queue-name))]
      (when (empty? urls)
        (Thread/sleep 200)
        (recur))))
    queue-url))

(defn send-message [queue-url payload]
  (.sendMessage sqs-client
                queue-url
                (generate-string payload)))

(defn receive-message [queue-url wait-in-seconds]
  (let [from-json #(parse-string % true)
        raw-message (-> (.receiveMessage sqs-client (-> (ReceiveMessageRequest.)
                                        (.withQueueUrl queue-url)
                                        (.withWaitTimeSeconds (int wait-in-seconds))))
        (.getMessages)
        (first))]
    (when raw-message
      (.deleteMessage sqs-client queue-url (.getReceiptHandle raw-message))
      (-> raw-message .getBody from-json))))

(defn receive-messages [queue-url wait-in-seconds handler-fn]
  (let [to-json #(parse-string % true)
        raw-messages (-> (.receiveMessage sqs-client (-> (ReceiveMessageRequest.)
                                                         (.withQueueUrl queue-url)
                                                         (.withWaitTimeSeconds (int wait-in-seconds))))
                         (.getMessages))]
    (map (fn [raw-message]
           (let [handle (.getReceiptHandle raw-message)
                 payload (-> raw-message .getBody to-json)
                 ret-value (handler-fn payload)]
             (.deleteMessage sqs-client queue-url handle)
             ret-value))
         raw-messages)))

(defn delete-queue [queue-url]
  (.deleteQueue sqs-client queue-url))

(defn message-loop [f in-queue out-queue]
  (let [handler (fn [message]
                  (when (= "process" (:type message)
                           (let [result (f (:payload message))]
                             (send-message out-queue {:index (:index message)
                                                      :payload result}))))
                    (:type message))]
    (go-loop []
             (let [responses (receive-messages in-queue 5 handler)]
               (when-not (some #(= "stop" %) responses)
                 (recur))))))


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
               (let [messages [(receive-message in-queue 3)]]
                 (recur (send-ok-messages ret (aq/add-all buffer messages))))))
    {:results ret
     :stop-fn stop-fn}))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn queue-reduce [f xs in-queues out-queue]
  (doseq [in-queue in-queues]
    (message-loop (fn [x] (reduce f x)) in-queue out-queue))
  (let [{:keys [results stop-fn]} (handler-loop out-queue)
        batches (map (fn [a b] [a b]) (partition-all 4096 xs) (range))]
    (thread
      (doseq [[batch index] batches]
        (send-message (nth in-queues (mod index (count in-queues)))
                      {:type "process" :index index :payload batch})))
    (let [response (reduce (fn [acc _]
                             (f acc (:payload (<!! results))))
                           0
                           batches)]
      (doseq [in-queue in-queues]
        (send-message in-queue {:type "stop"}))
      (stop-fn)
      response)))

(defn uber-reduce [f xs node-count]
  (let [in-queues  (map (fn [_] (create-queue (uuid)))
                        (range node-count))
        out (create-queue (uuid))]
    (let [response (queue-reduce f xs in-queues out)]
      (Thread/sleep 200)
      (doseq [in-queue in-queues]
        (delete-queue in-queue))
      (delete-queue out)
      response)))

;(invoke-lambda (pr-str (s/fn [] (map #(* 2 %) [3 4 5]))) "distributed-transducers-poc" "eu-west-1")

;(let [f (generate-string {:command (pr-str (s/fn [] (+ 2 3)))})]
;  ((load-string (:command (parse-string f true)))))
