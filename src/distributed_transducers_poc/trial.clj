(ns distributed-transducers-poc.trial
  (:require [distributed-transducers-poc.rc :refer [super-fold]]
            [serializable.fn :as s]
            [clj-fuzzy.levensthein]
            [clojure.core.reducers :as r]))

(defn file-by-word [file]
  (clojure.string/split (slurp file) #"\s+"))

(defn- dictionary [] (file-by-word "/usr/share/dict/words"))

(defn append-by-distance
  ([] {})
  ([e] e)
  ([acc [d w]]
     (update acc d #(set (conj % w)))))

(def dictionary-words ["word" "sword" "lord" "book"])

(defn levensthein-distance [word1 word2]
  [(clj-fuzzy.levensthein/distance word1 word2) word2])

(defn similar-words-1 [word words min-distance]
  (->> words
       (map (partial levensthein-distance word))
       (filter (fn [[d _]] (<= d min-distance)))
       (reduce append-by-distance {})))

(defn similar-words-2 [word words min-distance]
  (transduce (comp (map (partial levensthein-distance word))
                   (filter (fn [[d _]] (<= d min-distance))))
             append-by-distance
             words))

;(similar-words-2 "word" ["sword" "lord" "card" "cat"] 2)

(defn similar-words-3 [word words min-distance]
  (r/fold (partial merge-with concat)
          append-by-distance
          (r/folder words
                    (comp (map (partial levensthein-distance word))
                          (filter (fn [[d _]] (<= d min-distance)))))))

;(similar-words-3 "word" ["sword" "lord" "card" "cat"] 2)

(defn similar-words-4 [word words min-distance]
  (r/fold (partial merge-with concat)
          ((comp (map (partial levensthein-distance word))
                   (filter (fn [[d _]] (<= d min-distance)))) append-by-distance)
          words))

;(similar-words-4 "word" (take 50000 (file-by-word "resources/lilja.txt")) 2)

(defn similar-words-5 [word words min-distance]
  (r/fold (partial merge-with concat)
          ((comp (map (partial levensthein-distance word))
                   (filter (fn [[d _]] (<= d min-distance)))) append-by-distance)
          (vec words)))

;(similar-words-5 "word" (take 50000 (file-by-word "resources/lilja.txt")) 2)

(defn similar-words-6 [word words min-distance]
  (super-fold (partial merge-with concat)
              ((comp (map (partial distributed-transducers-poc.trial/levensthein-distance word))
                     (filter (fn [[d _]] (<= d min-distance)))) distributed-transducers-poc.trial/append-by-distance)
              words
              10))

(comment
(time
(super-fold (partial merge-with concat)
              ((comp (map (partial distributed-transducers-poc.trial/levensthein-distance "word"))
                     (filter (fn [[d _]] (<= d 2)))) distributed-transducers-poc.trial/append-by-distance)
              (dictionary)
              40))
)

;Example from david nolen
;(fold + ((map inc) +) (vec (range 1000000)))
