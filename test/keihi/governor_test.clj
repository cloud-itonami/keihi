(ns keihi.governor-test
  "The structural invariants: provenance, actuation, receipt basis, ownership,
  arithmetic, and the two escalations that are not about tax.

  The statute-driven rules have their own suite in `keihi.tax-rules-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-employee! st {:employee-id "emp-1" :name "田中花子"
                                  :jurisdiction :jp})
    (store/register-receipt! st {:receipt-id "rc-1" :employee-id "emp-1"
                                 :kind :receipt :amount 5000
                                 :origin :paper :preservation :paper})
    st))

(defn- claim
  "A clean, arithmetically consistent 5,000-yen claim."
  [& {:as overrides}]
  (merge {:op :submit-claim :effect :propose :receipt "rc-1"
          :amount 5000
          :lines [{:purpose "客先訪問の交通費" :amount 3000}
                  {:purpose "会議室利用料" :amount 2000}]
          :confidence 0.9 :stake :low}
         overrides))

(defn- rules-of [v] (into #{} (map :rule) (:violations v)))

(deftest ok-on-a-clean-claim
  (let [v (governor/check {:employee-id "emp-1"} {} (claim) (fresh-store))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (empty? (:violations v)))))

(deftest hard-on-unregistered-employee
  (let [v (governor/check {:employee-id "ghost"} {} (claim) (fresh-store))]
    (is (:hard? v))
    (is (contains? (rules-of v) :no-employee))))

(deftest hard-on-actuation
  (testing "the advisor may only propose — a proposal that says otherwise is
            refused whatever else is true of it"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (claim :effect :direct-payment) (fresh-store))]
      (is (:hard? v))
      (is (contains? (rules-of v) :no-actuation)))))

(deftest hard-on-a-claim-citing-no-receipt
  (testing "a reimbursement with no receipt is an invented expense"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (claim :receipt nil) (fresh-store))]
      (is (:hard? v))
      (is (contains? (rules-of v) :no-receipt)))))

(deftest hard-on-a-receipt-that-does-not-exist
  (testing "citing nothing and citing something unregistered are different
            failures, and only the second is :unknown-receipt"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (claim :receipt "rc-999") (fresh-store))]
      (is (:hard? v))
      (is (contains? (rules-of v) :unknown-receipt))
      (is (not (contains? (rules-of v) :no-receipt))))))

(deftest hard-on-another-employees-receipt
  (testing "without this, knowing a receipt id is enough to be paid for
            someone else's dinner"
    (let [st (fresh-store)]
      (store/register-employee! st {:employee-id "emp-2" :name "佐藤太郎"
                                    :jurisdiction :jp})
      (let [v (governor/check {:employee-id "emp-2"} {} (claim) st)]
        (is (:hard? v))
        (is (contains? (rules-of v) :receipt-wrong-employee))))))

(deftest hard-on-amount-not-equal-to-the-sum-of-lines
  (testing "whichever number is wrong, nobody knows which — paying either
            pays a figure no document supports"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (claim :amount 5500) (fresh-store))
          detail (:detail (first (filter #(= :amount-mismatch (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (contains? (rules-of v) :amount-mismatch))
      (is (re-find #"5500" detail) "the hold shows both figures")
      (is (re-find #"5000" detail)))))

(deftest hard-on-a-claim-with-no-amount-at-all
  (testing "a missing total must not become a coincidental 0 = 0 pass"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (claim :amount nil) (fresh-store))]
      (is (:hard? v))
      (is (contains? (rules-of v) :amount-mismatch)))))

(deftest a-single-line-claim-adds-up
  (let [v (governor/check {:employee-id "emp-1"} {}
                          (claim :amount 5000
                                 :lines [{:purpose "書籍" :amount 5000}])
                          (fresh-store))]
    (is (:ok? v))))

(deftest escalates-disbursement
  (testing "real money leaving the company is never an unattended commit"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            {:op :disburse :effect :propose :amount 5000
                             :confidence 0.99 :stake :high}
                            (fresh-store))]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (= :counsel-decision (:escalation-reason v))))))

(deftest escalates-low-confidence
  (let [v (governor/check {:employee-id "emp-1"} {}
                          (claim :confidence 0.3) (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (= :low-confidence (:escalation-reason v)))))

(deftest an-absent-confidence-key-is-zero-not-trust
  (testing "a proposal that has not said how confident it is has not said it
            is confident"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (dissoc (claim) :confidence) (fresh-store))]
      (is (= 0.0 (:confidence v)))
      (is (:escalate? v))
      (is (not (:ok? v))))))

(deftest a-rejection-is-not-blocked-by-the-claim-it-rejects
  (testing "the receipt rules are scoped to the ops that assert an expense —
            a governor that held the rejection would trap a bad claim"
    (let [st (store/mem-store)]
      (store/register-employee! st {:employee-id "emp-1" :name "田中花子"
                                    :jurisdiction :jp})
      (store/register-receipt! st {:receipt-id "rc-bad" :employee-id "emp-1"
                                   :kind :invoice-received
                                   :origin :electronic-transaction
                                   :preservation :paper})
      (let [v (governor/check {:employee-id "emp-1"} {}
                              {:op :reject-claim :effect :propose
                               :receipt "rc-bad" :confidence 0.9} st)]
        (is (:ok? v))
        (is (empty? (:violations v)))
        (is (nil? (:tax v)) "nothing was assessed, and the verdict says so")))))

(deftest several-failures-are-all-reported
  (testing "a hold that names one of four problems sends the operator round
            the loop three more times"
    (let [v (governor/check {:employee-id "ghost"} {}
                            (claim :effect :direct-payment :receipt nil
                                   :amount 9999)
                            (fresh-store))]
      (is (:hard? v))
      (is (= #{:no-employee :no-actuation :no-receipt :amount-mismatch}
             (rules-of v))))))
