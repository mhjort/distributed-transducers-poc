(ns distributed-transducers-poc.rc
  (:require [cheshire.core :refer [generate-string parse-string parse-stream]]
            [clojure.core.async :refer [chan thread go go-loop >! <! <!!]]
            [clojure.java.io :as io]
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

(defn plus [a b]
  (+ a b))

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
                             (println "Result " result)
                             (send-message out-queue {:index (:index message)
                                                      :payload result})))
                    (:type message)))]
    (go-loop []
             (let [responses (receive-messages in-queue 5 handler)]
               (when-not (some #(= "stop" %) responses)
                 (recur))))))

(defn uber-queue [key-fn]
  {:next-items []
   :next-index 0
   :key-fn key-fn
   :queue (java.util.PriorityQueue. 10 (comparator (fn [x y] (< (key-fn x) (key-fn y)))))})

(defn add [{:keys [key-fn queue] :as uber} element]
  (.add queue element)
  (loop [{:keys [next-items next-index] :as result} uber]
    (if (not (= next-index (key-fn (.peek queue))))
      result
      (do
        (let [e (.poll queue)]
          (recur {:next-items (sort-by :index (cons e next-items))
                  :next-index (inc next-index)
                  :key-fn key-fn
                  :queue queue}))))))

(defn add-all [{:keys [next-items next-index key-fn queue] :as uber} elements]
  (if (empty? elements)
    uber
    (add-all (add uber (first elements)) (drop 1 elements))))

(defn remove-one [uber]
  (update uber :next-items (partial drop 1)))

(defn head [{:keys [next-items]}]
  (first next-items))

(-> (uber-queue :index)
    (add {:index 0})
    (remove-one)
    (add {:index 2})
    (add {:index 1}))

(defn send-ok-messages [c buffer]
  (if-let [result (head buffer)]
    (do
      (println "Sending ok" result)
      (println "Sending budd" buffer)
      (go (>! c result))
      (send-ok-messages c (remove-one buffer)))
    buffer))

(defn handler-loop [in-queue]
  (let [continue? (atom true)
        stop-fn #(reset! continue? false)
        buffer (uber-queue :index)
        ret (chan 10)]
    (go-loop [buffer (uber-queue :index)]
             (when @continue?
               (let [messages [(receive-message in-queue 3)]]
                 (recur (send-ok-messages ret (add-all buffer messages))))))
    {:results ret
     :stop-fn stop-fn}))

(defn uuid [] (str (java.util.UUID/randomUUID)))

;(create-queue (uuid))

(defn queue-reduce [f xs in-queue out-queue]
  (message-loop (fn [x] (reduce f x)) in-queue out-queue)
  (let [{:keys [results stop-fn]} (handler-loop out-queue)
        batches (map (fn [a b] [a b]) (partition-all 1024 xs) (range))]
    (thread
      (doseq [[batch index] batches]
        (send-message in-queue {:type "process" :index index :payload batch})))
    (let [response (reduce (fn [acc _]
                             (f acc (:payload (<!! results))))
                           0
                           batches)]
      (send-message in-queue {:type "stop"})
      (stop-fn)
      response)))

;(comment
(defn do-reduce []
  (let [in (create-queue (uuid))
        out (create-queue (uuid))]
    (let [response (queue-reduce + (range 10000) in out)]
      (delete-queue in)
      (delete-queue out)
      response)))
  ;(super-reduce plus (range 100000))



;(invoke-lambda (pr-str (s/fn [] (map #(* 2 %) [3 4 5]))) "distributed-transducers-poc" "eu-west-1")

;(let [f (generate-string {:command (pr-str (s/fn [] (+ 2 3)))})]
;  ((load-string (:command (parse-string f true)))))
