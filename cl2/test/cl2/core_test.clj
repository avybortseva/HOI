(ns cl2.core-test
  (:require [clojure.test :refer :all]
            [cl2.core :refer :all]))

(deftest test-primes-first-few
  (is (= [2 3 5 7 11 13 17 19 23 29]
         (take 10 (primes)))))

(deftest test-primes-take-0
  (is (= '() (take 0 (primes)))))

(deftest test-primes-take-1
  (is (= '(2) (take 1 (primes)))))

(deftest test-primes-does-not-throw
  (is (= 1000 (count (take 1000 (primes))))))
