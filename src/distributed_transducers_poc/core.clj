(ns distributed-transducers-poc.core
  (:require [uswitch.lambada.core :refer [deflambdafn]]))

(deflambdafn distributed-transducers-poc.LambdaFn
  [in out ctx]
  (println "OMG I'm running in the cloud!!!111oneone"))

