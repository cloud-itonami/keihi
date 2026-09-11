(ns keihi.actor-test
  "End-to-end through the StateGraph. The governor suites prove what the
  verdict says; these prove that the graph obeys it — that a hold writes no
  record and moves no money, and that an escalation stops before the commit
  node rather than after it."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.actor :as actor]
            [keihi.advisor :as advisor]
            [keihi.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-employee! st {:employee-id "emp-1" :name "田中花子"
                                  :jurisdiction :jp})
    (store/register-receipt! st {:receipt-id "rc-1" :employee-id "emp-1"
                                 :kind :invoice-received :amount 5000
                                 :registration-number "T1234567890123"
                                 :origin :paper :preservation :paper})
    st))

(def ^:private lines [{:purpose "客先訪問の交通費" :amount 5000}])

(deftest commits-a-clean-claim
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:employee-id "emp-1" :op :submit-claim :stake :low
                 :receipt "rc-1" :amount 5000 :lines lines}
        result (actor/run-request! graph request {} "t-1")]
    (is (= :done (:status result)))
    (is (= "rc-1" (get-in result [:state :record :receipt])))
    (is (= 5000 (get-in result [:state :record :amount])))
    (is (= 1 (count (store/records-of st "emp-1"))))))

(deftest holds-an-invented-expense-without-writing-anything
  (testing "no receipt -> HARD hold, nothing written"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:employee-id "emp-1" :op :submit-claim :stake :low
                   :receipt nil :amount 5000 :lines lines}
          result (actor/run-request! graph request {} "t-2")]
      (is (= :done (:status result)))
      (is (nil? (get-in result [:state :record])))
      (is (empty? (store/records-of st "emp-1")))
      (is (= :hold (get-in result [:state :disposition]))))))

(deftest holds-a-claim-that-does-not-add-up
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:employee-id "emp-1" :op :submit-claim :stake :low
                 :receipt "rc-1" :amount 6000 :lines lines}
        result (actor/run-request! graph request {} "t-3")]
    (is (= :hold (get-in result [:state :disposition])))
    (is (empty? (store/records-of st "emp-1")))))

(deftest interrupts-then-commits-a-disbursement-on-human-approval
  (testing "money does not leave the company on an unattended graph run"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:employee-id "emp-1" :op :disburse :stake :high
                   :amount 5000}
          interrupted (actor/run-request! graph request {} "t-4")]
      (is (= :interrupted (:status interrupted)))
      (is (empty? (store/records-of st "emp-1"))
          "nothing is written while the approval is pending")
      (let [resumed (actor/approve! graph "t-4")]
        (is (= :done (:status resumed)))
        (is (some? (get-in resumed [:state :record])))
        (is (= 1 (count (store/records-of st "emp-1"))))))))

(deftest a-hold-is-recorded-in-the-ledger
  (testing "an audit trail of only the operations that succeeded is an audit
            trail of the wrong thing"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph
                          {:employee-id "emp-1" :op :submit-claim
                           :receipt nil :amount 5000 :lines lines}
                          {} "t-5")
      (let [entries (store/ledger st)]
        (is (= 1 (count entries)))
        (is (= :hold (:disposition (first entries))))
        (is (seq (get-in (first entries) [:verdict :violations]))
            "the ledger says what was refused, not merely that something was")))))

(deftest a-commit-records-the-verdict-alongside-the-record
  (testing "`what was paid` without `what was checked` cannot be audited"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph
                          {:employee-id "emp-1" :op :submit-claim
                           :receipt "rc-1" :amount 5000 :lines lines}
                          {} "t-6")
      (let [entry (first (store/ledger st))]
        (is (= :commit (:disposition entry)))
        (is (true? (get-in entry [:verdict :ok?])))
        (is (some? (:record entry)))))))

(deftest the-advisor-cannot-write-past-the-governor
  (testing "an advisor that emits a non-:propose effect still commits nothing"
    (let [st (fresh-store)
          rogue (reify advisor/Advisor
                  (-advise [_ _store request]
                    {:op :submit-claim
                     :effect :direct-payment
                     :receipt "rc-1"
                     :amount (:amount request)
                     :lines lines
                     :confidence 1.0}))
          graph (actor/build-graph {:store st :advisor rogue})
          result (actor/run-request! graph
                                     {:employee-id "emp-1" :op :submit-claim
                                      :amount 5000}
                                     {} "t-7")]
      (is (= :hold (get-in result [:state :disposition])))
      (is (empty? (store/records-of st "emp-1"))))))

;; ---------------------------------------------------------------------------
;; Routing a verdict this governor cannot produce
;; ---------------------------------------------------------------------------

(deftest a-hard-hold-is-never-routed-to-approval-even-if-the-verdict-drifts
  (testing "MEASURED 2026-08-18: swapping the two clauses of `decide` survived
            the whole suite. `governor.core/verdict` makes `:hard?` and
            `:escalate?` mutually exclusive, so no verdict THIS governor emits
            can tell the order apart — and the clause order exists precisely
            for a governor that has drifted, which is the failure
            `kotoba-lang/governor` measured in one of 376 copies. The only way
            to measure the defence is to hand it the drift"
    (is (= :hold (actor/decide {:hard? true :escalate? true}))
        "an approver must not be invited to wave through a HARD hold")
    (is (= :hold (actor/decide {:hard? true :escalate? true :ok? true}))
        "nor may a verdict claiming to be ok route past it")))

(deftest decide-covers-the-three-dispositions-it-can-return
  ;; Evidence floor: the assertion above is about ONE malformed shape, and a
  ;; `decide` that returned :hold unconditionally would satisfy it.
  (is (= :hold (actor/decide {:hard? true})))
  (is (= :request-approval (actor/decide {:escalate? true})))
  (is (= :commit (actor/decide {:ok? true})))
  (is (= :commit (actor/decide {}))
      "an empty verdict is not a shape this governor emits either; recorded
       so that changing it is a decision rather than an accident"))
