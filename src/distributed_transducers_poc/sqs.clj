(ns distributed-transducers-poc.sqs
  (:require [cheshire.core :refer [generate-string parse-string]])
  (:import [com.amazonaws.regions Regions]
           [com.amazonaws.services.sqs AmazonSQSClient]
           [com.amazonaws.services.sqs.model ReceiveMessageRequest]))

(def sqs-client (-> (AmazonSQSClient.)
                    (.withRegion (Regions/fromName "eu-west-1"))))

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

