(ns airfreightops.store-contract-test
  "Contract tests for `airfreightops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [airfreightops.store :as store]))

(deftest mem-store-facility-lookup
  (testing "MemStore can store and retrieve facilities by ID (string keys)"
    (let [facilities {"f1" {:facility-id "f1" :name "Alice's Air Cargo Terminal" :registered? true :verified? true}}
          s (store/mem-store facilities)]
      (is (some? (store/facility-record s "f1")))
      (is (nil? (store/facility-record s "f99"))))))

(deftest mem-store-all-facility-records
  (testing "MemStore returns all facilities in sorted order"
    (let [facilities {"f2" {:facility-id "f2" :name "Bob's Cargo Warehouse"}
                       "f1" {:facility-id "f1" :name "Alice's Air Cargo Terminal"}
                       "f3" {:facility-id "f3" :name "Carol's Ramp Facility"}}
          s (store/mem-store facilities)
          all-f (store/all-facility-records s)]
      (is (= 3 (count all-f)))
      (is (= "f1" (:facility-id (first all-f))))
      (is (= "f3" (:facility-id (last all-f)))))))

(deftest mem-store-contractor-lookup
  (testing "MemStore can store and retrieve contractors by ID (string keys)"
    (let [contractors {"c1" {:contractor-id "c1" :name "Acme GSE Maintenance" :registered? true :verified? true}}
          s (store/mem-store {} contractors)]
      (is (some? (store/contractor-record s "c1")))
      (is (nil? (store/contractor-record s "c99"))))))

(deftest mem-store-all-contractor-records
  (testing "MemStore returns all contractors in sorted order"
    (let [contractors {"c2" {:contractor-id "c2" :name "Beta GSE Supply"}
                        "c1" {:contractor-id "c1" :name "Acme GSE Maintenance"}}
          s (store/mem-store {} contractors)
          all-c (store/all-contractor-records s)]
      (is (= 2 (count all-c)))
      (is (= "c1" (:contractor-id (first all-c)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-shipment-record :facility-id "f1" :value {:piece-count 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-facility-records
  (testing "MemStore with-facility-records replaces the facility directory"
    (let [s (store/mem-store {})
          new-facilities {"f1" {:facility-id "f1" :name "Alice's Air Cargo Terminal"}}]
      (is (= 0 (count (store/all-facility-records s))))
      (store/with-facility-records s new-facilities)
      (is (= 1 (count (store/all-facility-records s)))))))

(deftest mem-store-with-contractor-records
  (testing "MemStore with-contractor-records replaces the contractor directory"
    (let [s (store/mem-store {})
          new-contractors {"c1" {:contractor-id "c1" :name "Acme GSE Maintenance"}}]
      (is (= 0 (count (store/all-contractor-records s))))
      (store/with-contractor-records s new-contractors)
      (is (= 1 (count (store/all-contractor-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo facilities and contractors"
    (let [s (store/seed-db)]
      (is (> (count (store/all-facility-records s)) 0))
      (is (some? (store/facility-record s "facility-1")))
      (is (some? (store/facility-record s "facility-2")))
      (is (some? (store/facility-record s "facility-3")))
      (is (> (count (store/all-contractor-records s)) 0))
      (is (some? (store/contractor-record s "contractor-1")))
      (is (some? (store/contractor-record s "contractor-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for facility-id/contractor-id"
    (let [demo (store/demo-data)
          facilities (:facilities demo)
          contractors (:contractors demo)]
      (doseq [[k v] facilities]
        (is (string? k) "facility keys must be strings")
        (is (string? (:facility-id v)) "facility-id must be string")
        (is (= k (:facility-id v)) "key must match facility-id"))
      (doseq [[k v] contractors]
        (is (string? k) "contractor keys must be strings")
        (is (string? (:contractor-id v)) "contractor-id must be string")
        (is (= k (:contractor-id v)) "key must match contractor-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
