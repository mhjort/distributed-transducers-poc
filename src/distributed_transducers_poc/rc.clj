(ns distributed-transducers-poc.rc
  (:require [cheshire.core :refer [generate-string parse-string parse-stream]]
            [clojure.core.async :refer [go]]
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

(defn delete-queue [queue-url]
  (.deleteQueue sqs-client queue-url))

(defn message-handler [f in-queue out-queue]
  (go
    (let [payload (receive-message in-queue 5)
          response {:result (f (:param payload))}]
      (send-message out-queue response))))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn queue-reduce [f xs in-queue out-queue]
  (let [batches (partition-all 1024 xs)]
    (reduce (fn [acc batch]
              (send-message in-queue {:param batch})
              (message-handler #(reduce f %) in-queue out-queue)
              (let [response (receive-message out-queue 5)]
                (f acc (:result response))))
            0
            batches)))

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
