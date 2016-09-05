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

(defn file-by-word [file]
  (clojure.string/split (slurp file) #"\s+"))

(defn accu
  ([] {})
  ([similar-words word]
   (println similar-words word)
   (let [distance (clj-fuzzy.levensthein/distance "book" word)]
     (update similar-words distance #(set (conj % word))))))

(defn similar? [minimum-distance]
  (filter (fn [[k v]]
            (<= k minimum-distance))))

(defn distance [word]
  (map (fn [other]
         (let [distance (clj-fuzzy.levensthein/distance word other)]
           [distance other]))))

(defn accu2
  ([] {})
  ([e] e)
  ([acc [k v]]
     (update acc k #(set (conj % v)))))

#(merge-with concat)

;(time
;(transduce (comp (distance "book") (similar? 2)) accu2 (take 50000 (file-by-word "resources/lilja.txt")))
;)

;Example from david nolen
;(fold + ((map inc) +) (vec (range 1000000)))

;Finally working version!!!
(comment
(time
  (r/fold (partial merge-with concat)
          ((comp (distance "book") (similar? 2)) accu2)
          (vec (take 50000 (file-by-word "resources/lilja.txt"))))) ;Note! vec forces item to be non-lazy so that parallel fold works
)

(comment
(time
  (r/fold (partial merge-with concat)
          accu2
          (r/folder (take 50000 (file-by-word "resources/lilja.txt"))
                    (comp (distance "book") (similar? 2)))))
)

(r/folder (take 50000 (file-by-word "resources/lilja.txt"))
                    (comp (distance "book") (similar? 2)))

;(r/fold accu (file-by-word "resources/lilja.txt"))


;(reduce accu {} ["word" "cat" "car" "car"])

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
