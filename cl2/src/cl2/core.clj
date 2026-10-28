(ns cl2.core
  (:use clojure.test))

(defn primes []
  (letfn [(sieve [numbers]
                (lazy-seq
                  (let [p (first numbers)]
                    (cons p
                          (sieve
                            (filter
                              (fn [x]
                                (not= 0 (mod x p)))
                              (rest numbers)))))))]
  (sieve (iterate inc 2))))

(take 0 (primes))
(take 10 (primes))
(take 20 (primes))
(take 5 (primes))


