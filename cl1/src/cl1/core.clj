(ns cl1.core)

(defn gen-strings [alphabet n]
  (reduce
    (fn [strings _]
      (reduce
        concat
        (map
          (fn [s]
            (map
              (fn [ch] (str s ch))
              (remove
                (fn [ch]
                  (= (str(last s)) ch))
                alphabet)))
          strings)))
    '("") (range n)))

(gen-strings '("a" "b" "c") 2)

(gen-strings '("a" "b" "c") 2)

(gen-strings '("a" "b") 3)

(gen-strings '("a" "b" "c") 1)

(gen-strings '("a" "b") 0)
