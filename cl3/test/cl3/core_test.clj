(ns cl3.core_test
  (:require [clojure.test :refer [deftest is testing]]
            [cl3.core :refer [parallel-filter]]))

(def small-blocks {:block-size 2 :parallelism 2})

(deftest empty-input
  (is (empty? (parallel-filter even? [])))
  (is (empty? (parallel-filter even? nil))))

(deftest standard-input
  (is (= [4 2 4 6]
         (vec (parallel-filter even? [4 1 2 4 3 6] small-blocks)))))

(deftest large-input
  (let [xs (range 10000)]
    (is (= (vec (filter even? xs))
           (vec (parallel-filter even? xs
                                 {:block-size 128 :parallelism 4}))))))

(deftest edge-cases
  (testing "Один элемент проходит или не проходит фильтр"
    (is (= [2] (vec (parallel-filter even? [2] small-blocks))))
    (is (empty? (parallel-filter even? [1] small-blocks))))

  (testing "Целый блок без совпадений, затем неполный последний блок"
    (is (= [2 4]
           (vec (parallel-filter even? [1 3 2 5 4] small-blocks)))))

  (testing "Все элементы подходят"
    (is (= [2 4 6]
           (vec (parallel-filter even? [2 4 6] small-blocks))))))

(deftest infinite-input
  (is (= [0 2 4 6 8]
         (vec (take 5
                    (parallel-filter even? (iterate inc 0) small-blocks))))))
