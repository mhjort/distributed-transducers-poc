(ns distributed-transducers-poc.trial
  (:require [distributed-transducers-poc.rc :refer [super-fold]]
            [clj-fuzzy.levensthein]
            [clojure.core.reducers :as r]))

(defn count-words
    ([] {})
      ([freqs word]
           (assoc freqs word (inc (get freqs word 0)))))

(defn merge-counts
    ([] {})
      ([& m] (apply merge-with + m)))

(defn word-frequency [text]
    (r/fold merge-counts count-words (clojure.string/split text #"\s+")))

(def xf (comp (filter odd?) (map inc)))
(transduce xf + (range 5))
;; => 6


;(time (take 10 (doall (pmap #(clj-fuzzy.levensthein/distance "book" %) (clojure.string/split (slurp "resources/lilja.txt") #"\s+")))))
; Big file local
;"Elapsed time: 452925.488452 msecs"

; (time (doall (take 10 (word-frequency (slurp "resources/big.txt")))))

;(r/fold concat #(conj %1 (clj-fuzzy.levensthein/distance "book" %2)) ["backi" "buck" "yyy"])

(comment
(time (doall (take 10 (super-fold concat
                                  #(conj %1 (clj-fuzzy.levensthein/distance "book" %2))
                                  (clojure.string/split (slurp "resources/big.txt") #"\s+")
                                  10))))
)
; Big file 10 Lambdas
; "Elapsed time: 109665.06582 msecs"
; (11 4 5 3 6 5 4 4 4 4)


(comment
(time (doall (take 10 (super-fold distributed-transducers-poc.trial/merge-counts
                                  distributed-transducers-poc.trial/count-words
                                  (clojure.string/split (slurp "resources/big.txt") #"\s+")
                                  3))))
)

;(super-fold + + (range 100000))

;(invoke-lambda (pr-str (s/fn [] (map #(* 2 %) [3 4 5]))) "distributed-transducers-poc" "eu-west-1")

;(let [f (generate-string {:command (pr-str (s/fn [] (+ 2 3)))})]
;  ((load-string (:command (parse-string f true)))))
