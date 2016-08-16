(ns distributed-transducers-poc.rc
  (:require [cheshire.core :refer [generate-string parse-string parse-stream]]
            [clojure.core.async :refer [chan go go-loop >! <! <!!]]
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
  (let [to-json #(parse-string % true)]
    (-> (.receiveMessage sqs-client (-> (ReceiveMessageRequest.)
                                        (.withQueueUrl queue-url)
                                        (.withWaitTimeSeconds (int wait-in-seconds))))
        (.getMessages)
        (first)
        (.getBody)
        (to-json))))

(defn receive-messages [queue-url wait-in-seconds]
  (let [to-json #(parse-string % true)
        raw-messages (-> (.receiveMessage sqs-client (-> (ReceiveMessageRequest.)
                                        (.withQueueUrl queue-url)
                                        (.withWaitTimeSeconds (int wait-in-seconds))))
        (.getMessages))]
    (map #(-> % .getBody to-json) raw-messages)))

(defn delete-queue [queue-url]
  (.deleteQueue sqs-client queue-url))

(defn message-handler [f in-queue out-queue]
  (go
    (let [payload (receive-message in-queue 5)
          response {:result (f (:param payload))}]
      (send-message out-queue response))))

(defn message-loop [f in-queue out-queue]
  (go-loop []
     (let [messages (receive-message in-queue 5)]
       (doseq [message (filter #(= "process" (:type "message")) messages)]
          (send-message out-queue {:result (f (:param message))}))
       (when-not (some #(= "stop" (:type %)) messages)
         (recur)))))

(defn uber-queue [key-fn]
  {:next-items []
   :next-index 0
   :key-fn key-fn
   :queue (java.util.PriorityQueue. 10 (comparator (fn [x y] (< (key-fn x) (key-fn y)))))})

(defn add [{:keys [next-items next-index key-fn queue] :as uber} element]
  (.add queue element)
  (if (= next-index (key-fn (.peek queue)))
    (do
      (.poll queue)
      {:next-items (conj next-items element)
       :next-index (inc next-index)
       :key-fn key-fn
       :queue queue})
    uber))

(defn add-all [{:keys [next-items next-index key-fn queue] :as uber} elements]
  (if (empty? elements)
    uber
    (add-all (add uber (first elements)) (drop 1 elements))))

(defn vittu [uber]
  (update uber :next-items (partial drop 1)))

(defn head [{:keys [next-items]}]
  (first next-items))

(defn send-ok-messages [c buffer]
  (if-let [result (head buffer)]
    (do
      (go (>! c result))
      (send-ok-messages c (vittu buffer)))
    buffer))

(defn handler-loop [in-queue max-index]
  (let [buffer (uber-queue :index)
        ret (chan 10)]
    (go-loop [next-index 0
              buffer (uber-queue :index)]
             (when (< next-index max-index)
               (let [messages (receive-messages in-queue 4)]
                 (recur (inc next-index)
                        (send-ok-messages ret (add-all buffer messages))))))
    ret))

(defn uuid [] (str (java.util.UUID/randomUUID)))

;(create-queue (uuid))

;(comment
(defn lol []
 (let [queue (create-queue (uuid))
      a (handler-loop queue 4)]
  (println (send-message queue {:index 0 :result "a"}))
  (send-message queue {:index 2 :result "b"})
  (send-message queue {:index 1 :result "b"})
  (send-message queue {:index 3 :result "b"})
  [(<!! a) (<!! a) (<!! a) (<!! a)]))
;)

;(lol)


(defn queue-reduce [f xs in-queue out-queue]
  (message-loop #(reduce f %) in-queue out-queue)
  (let [batches (partition-all 1024 xs)
        response (reduce (fn [acc batch]
                           (send-message in-queue {:type "process" :param batch})
                           (let [response (receive-message out-queue 5)]
                             (f acc (:result response))))
                         0
                         batches)]
    (send-message in-queue {:type "stop"})
    response))

(comment
(let [in (create-queue (uuid))
      out (create-queue (uuid))]
  (let [response (queue-reduce + (range 10000) in out)]
    (delete-queue in)
    (delete-queue out)
    response))
)
  ;(super-reduce plus (range 100000))

;(invoke-lambda (pr-str (s/fn [] (map #(* 2 %) [3 4 5]))) "distributed-transducers-poc" "eu-west-1")

;(let [f (generate-string {:command (pr-str (s/fn [] (+ 2 3)))})]
;  ((load-string (:command (parse-string f true)))))
