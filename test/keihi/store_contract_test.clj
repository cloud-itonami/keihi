(ns keihi.store-contract-test
  "MemStore ≡ DatomicStore for the keihi store.

  Every assertion in this file runs against BOTH backends. That is the whole
  point of it: a second backend that quietly disagrees with the first is worse
  than no second backend at all, because the disagreement only shows up in
  production, on the deployment that has the durable one.

  Two properties are load-bearing here and the file is organised around them.

  **The ledger is ordered and append-only.** It is the only record of what this
  actor refused and why. A backend that returned it out of order would produce
  an audit trail in which the amendment precedes the claim, and nothing in the
  actor would notice — the graph never reads the ledger back.

  **The three statute-bearing receipt fields survive the round trip.**
  `:origin`, `:preservation` and `:registration-number` are what 電子帳簿保存法
  第七条, 消費税法 第三十条第七項 and the 登録番号 rule actually turn on. A backend
  that dropped one of them would turn a HARD hold into a clean commit, silently,
  and only on the durable deployment."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.actor :as actor]))

(def backends {:mem store/mem-store :datomic store/datomic-store})

(def ^:private employee
  {:employee-id "emp-1" :name "田中花子" :jurisdiction :jp})

(def ^:private receipt
  {:receipt-id "rc-1" :employee-id "emp-1" :kind :invoice-received :amount 5000
   :registration-number "T1234567890123"
   :origin :paper :preservation :paper})

(def ^:private lines [{:purpose "客先訪問の交通費" :amount 5000}])

(defn- seeded [make]
  (doto (make)
    (store/register-employee! employee)
    (store/register-receipt! receipt)))

(defn- each-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (seeded make)))))

(defn- each-empty-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (make)))))

;; ---------------------------------------------------------------------------
;; Directory reads
;; ---------------------------------------------------------------------------

(deftest an-employee-round-trips-with-the-jurisdiction-the-governor-reads
  (each-backend
   (fn [st]
     (is (= employee (store/employee st "emp-1")))
     (testing "the jurisdiction lives here and nowhere a proposal can reach it"
       (is (= :jp (:jurisdiction (store/employee st "emp-1")))))
     (testing "an unknown employee is nil, which is a HARD hold"
       (is (nil? (store/employee st "ghost")))))))

(deftest re-registering-an-employee-id-replaces-rather-than-forks
  (each-backend
   (fn [st]
     (store/register-employee! st (assoc employee :jurisdiction :atlantis))
     (is (= :atlantis (:jurisdiction (store/employee st "emp-1"))))
     (testing "one employee, relocated — not two disagreeing about the law
               that applies to them"
       (is (= 1 (count (distinct [(store/employee st "emp-1")]))))))))

(deftest the-three-statute-bearing-receipt-fields-survive-the-round-trip
  (testing "a backend that dropped one of these would turn a HARD hold into a
            clean commit, silently, and only where it is deployed"
    (each-backend
     (fn [st]
       (let [r (store/receipt st "rc-1")]
         (is (= :paper (:origin r)) "電子帳簿保存法 第七条 turns on :origin")
         (is (= :paper (:preservation r)) "消費税法 第三十条第七項 turns on :preservation")
         (is (= "T1234567890123" (:registration-number r)))
         (is (= receipt r)))))))

(deftest a-receipt-that-declares-nothing-round-trips-as-declaring-nothing
  (testing "absent :preservation and :none are different facts and must not
            converge on either backend"
    (each-backend
     (fn [st]
       (store/register-receipt! st {:receipt-id "rc-silent" :employee-id "emp-1"
                                    :kind :receipt :amount 100})
       (store/register-receipt! st (assoc receipt :receipt-id "rc-gone"
                                          :preservation :none))
       (is (nil? (:preservation (store/receipt st "rc-silent"))))
       (is (= :none (:preservation (store/receipt st "rc-gone"))))
       (is (nil? (store/receipt st "rc-999")))))))

(deftest an-empty-store-answers-empty-not-nil
  (each-empty-backend
   (fn [st]
     (is (empty? (store/records-of st "emp-1")))
     (is (empty? (store/ledger st)))
     (is (empty? (store/ledger-of st "emp-1")))
     (is (empty? (store/claim-history st "c-1"))))))

;; ---------------------------------------------------------------------------
;; The append-only streams
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-append-only-and-ordered
  (testing "an audit trail whose order depends on the backend is not one"
    (each-backend
     (fn [st]
       (doseq [n (range 6)]
         (store/append-ledger! st {:disposition :hold :employee-id "emp-1"
                                   :claim-id (str "c-" n) :n n}))
       (is (= 6 (count (store/ledger st))))
       (is (= [0 1 2 3 4 5] (mapv :n (store/ledger st))))
       (testing "and appending more does not disturb what is already there"
         (store/append-ledger! st {:disposition :commit :n 6})
         (is (= [0 1 2 3 4 5 6] (mapv :n (store/ledger st)))))))))

(deftest identical-ledger-facts-both-land-rather-than-de-duplicating
  (testing "two holds of the same claim for the same reason are two events;
            a store that collapsed them would under-report a repeated attempt"
    (each-backend
     (fn [st]
       (dotimes [_ 3]
         (store/append-ledger! st {:disposition :hold :claim-id "c-1"
                                   :employee-id "emp-1"}))
       (is (= 3 (count (store/ledger st))))))))

(deftest records-append-in-order-and-are-scoped-to-their-employee
  (each-backend
   (fn [st]
     (store/register-employee! st {:employee-id "emp-2" :jurisdiction :jp})
     (doseq [n (range 4)]
       (store/commit-record! st {:employee-id "emp-1" :amount (* 100 n) :n n}))
     (store/commit-record! st {:employee-id "emp-2" :amount 999 :n 99})
     (is (= [0 1 2 3] (mapv :n (store/records-of st "emp-1"))))
     (is (= [99] (mapv :n (store/records-of st "emp-2"))))
     (is (empty? (store/records-of st "emp-3"))))))

;; ---------------------------------------------------------------------------
;; Derived reads over the ledger
;; ---------------------------------------------------------------------------

(deftest claim-history-is-the-whole-life-of-one-claim-in-order
  (each-backend
   (fn [st]
     (store/append-ledger! st {:disposition :request-approval :claim-id "c-1"
                               :employee-id "emp-1"})
     (store/append-ledger! st {:disposition :hold :claim-id "c-2"
                               :employee-id "emp-1"})
     (store/append-ledger! st {:disposition :commit :claim-id "c-1"
                               :employee-id "emp-1"})
     (is (= [:request-approval :commit]
            (mapv :disposition (store/claim-history st "c-1"))))
     (testing "an id nobody has heard of is empty, which is not a verdict"
       (is (= [] (store/claim-history st "c-999"))))
     (testing "a nil id is nil, never every entry that carries no claim id"
       (store/append-ledger! st {:disposition :hold :employee-id "emp-1"})
       (is (nil? (store/claim-history st nil)))))))

(deftest ledger-of-scopes-to-one-employee
  (each-backend
   (fn [st]
     (store/append-ledger! st {:disposition :commit :claim-id "c-1"
                               :employee-id "emp-1"})
     (store/append-ledger! st {:disposition :commit :claim-id "c-2"
                               :employee-id "emp-2"})
     (is (= ["c-1"] (mapv :claim-id (store/ledger-of st "emp-1"))))
     (is (= ["c-2"] (mapv :claim-id (store/ledger-of st "emp-2"))))
     (is (nil? (store/ledger-of st nil))))))

;; ---------------------------------------------------------------------------
;; The actor runs unchanged on either backend
;; ---------------------------------------------------------------------------

(defn- run-claim [make request thread]
  (let [st (seeded make)
        g (actor/build-graph {:store st})
        r (actor/run-request! g request {} thread)]
    {:store st :graph g :result r}))

(defn- observed [{:keys [store result]}]
  {:status (:status result)
   :disposition (get-in result [:state :disposition])
   :records (count (store/records-of store "emp-1"))
   :ledger (mapv :disposition (store/ledger store))
   :claim-ids (mapv :claim-id (store/ledger store))
   :rules (mapv :rule (get-in result [:state :verdict :violations]))})

(def ^:private clean-claim
  {:employee-id "emp-1" :claim-id "c-1" :op :submit-claim :stake :low
   :receipt "rc-1" :amount 5000 :lines lines})

(deftest a-clean-claim-commits-identically-on-both-backends
  (let [run #(observed (run-claim % clean-claim "t-1"))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:status :done :disposition :commit :records 1
            :ledger [:commit] :claim-ids ["c-1"] :rules []}
           (run store/datomic-store)))))

(deftest an-invented-expense-is-held-identically-on-both-backends
  (let [run #(observed (run-claim % (assoc clean-claim :receipt nil :claim-id "c-2")
                                  "t-2"))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:status :done :disposition :hold :records 0
            :ledger [:hold] :claim-ids ["c-2"] :rules [:no-receipt]}
           (run store/datomic-store)))))

(deftest a-disbursement-escalates-then-commits-identically-on-both-backends
  (testing "the escalation is in the ledger BEFORE the human signs, on both
            backends — otherwise `awaiting approval` and `never submitted`
            are the same observation"
    (let [run (fn [make]
                (let [{:keys [store graph] :as ctx}
                      (run-claim make {:employee-id "emp-1" :claim-id "c-3"
                                       :op :disburse :stake :high :amount 5000}
                                 "t-3")
                      pending (observed ctx)]
                  (actor/approve! graph "t-3")
                  {:pending pending
                   :after-ledger (mapv :disposition (store/ledger store))
                   :after-records (count (store/records-of store "emp-1"))
                   :history (mapv :disposition (store/claim-history store "c-3"))}))]
      (is (= (run store/mem-store) (run store/datomic-store)))
      (is (= {:pending {:status :interrupted :disposition :request-approval
                        :records 0 :ledger [:request-approval]
                        :claim-ids ["c-3"] :rules []}
              :after-ledger [:request-approval :commit]
              :after-records 1
              :history [:request-approval :commit]}
             (run store/datomic-store))))))

(deftest what-the-statutes-could-not-say-survives-the-blob-round-trip
  (testing "the ledger carries the verdict, and the verdict's three-valued
            :tax report is the part a naive encoder would flatten"
    (let [run (fn [make]
                (let [st (make)]
                  (store/register-employee! st (assoc employee :jurisdiction :atlantis))
                  (store/register-receipt! st receipt)
                  (actor/run-request! (actor/build-graph {:store st})
                                      (assoc clean-claim :claim-id "c-4"
                                             :tax-treatment :input-tax-credit)
                                      {} "t-4")
                  (let [entry (last (store/ledger st))]
                    {:disposition (:disposition entry)
                     :credit-coverage (get-in entry [:verdict :tax :credit :taxlaw/coverage])
                     :invoice-coverage (get-in entry [:verdict :tax :invoice-preservation
                                                      :keihi/coverage])
                     :rules (mapv :rule (get-in entry [:verdict :violations]))})))]
      (is (= (run store/mem-store) (run store/datomic-store)))
      (is (= {:disposition :hold
              :credit-coverage :none
              :invoice-coverage :none
              :rules [:unchecked-jurisdiction]}
             (run store/datomic-store))))))

(deftest the-backends-are-actually-two
  ;; Evidence floor. Every test above loops over `backends`; if that map ever
  ;; lost an entry the whole file would keep passing while checking one
  ;; implementation, which is the state this file exists to end.
  (is (= 2 (count backends)))
  (is (= #{:mem :datomic} (set (keys backends))))
  (testing "and they really are different types"
    (is (not= (type (store/mem-store)) (type (store/datomic-store))))))
