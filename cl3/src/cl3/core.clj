(ns cl3.core)

(defn parallel-filter
  ([pred coll]

   (parallel-filter pred coll {}))

  ([pred coll {:keys [block-size parallelism]
               :or {block-size 256
                    parallelism (max 1 (min 8 (.availableProcessors
                                                (Runtime/getRuntime))))}}]
   (doseq [[k v] [[:block-size block-size] [:parallelism parallelism]]]
     (when-not (and (integer? v) (pos? v))
       (throw (IllegalArgumentException.
                (str (name k) " must be a positive integer")))))

   (letfn [(read-block [xs]
             (loop [xs xs, n block-size, block (transient [])]
               (if (zero? n)
                 [(persistent! block) xs]
                 (if-let [s (seq xs)]
                   (recur (rest s) (dec n) (conj! block (first s)))
                   [(persistent! block) nil]))))
           (fill-window [pending xs]
             (loop [pending pending, xs xs]
               (if (= (count pending) parallelism)
                 [pending xs]
                 (let [[block tail] (read-block xs)]
                   (if (empty? block)
                     [pending nil]
                     (recur (conj pending
                                  (future (into [] (filter pred) block)))
                            tail))))))

           (step [pending xs]
             (lazy-seq
               (loop [pending pending, xs xs]
                 (let [[pending xs] (fill-window pending xs)]
                   (when-let [job (peek pending)]
                     (let [values @job
                           pending (pop pending)]
                       (if (seq values)
                         (concat values (step pending xs))
                         (recur pending xs))))))))]
     (step clojure.lang.PersistentQueue/EMPTY coll))))

(let [data (vec (range 100))
      pred (fn [x]
             (Thread/sleep 10)
             (even? x))]
  (println "Обычный filter:")
  (println "Результат:" (time (vec (filter pred data))))

  (println "Параллельный filter:")
  (println "Результат:"
           (time (vec (parallel-filter pred data
                                       {:block-size 10 :parallelism 4})))))
