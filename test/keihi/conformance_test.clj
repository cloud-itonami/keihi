(ns keihi.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had silently drifted: it reported a HARD violation as
  escalatable, so an approval queue would show a permanently-refused operation
  as awaiting sign-off and invite a human to try to approve something no
  approval can pass. The drift was invisible through the actor's own graph —
  the router tests `:hard?` first — so no test caught it.

  This is the test that would have. It does not check WHAT the governor
  decided; the other suites do that. It checks that whatever it decided is
  internally consistent, across the whole space of dispositions this actor
  has, and that the space is actually covered."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [keihi.store :as store]
            [keihi.governor :as governor]
            [governor.core :as gov]))

(def ^:private jp-employee
  {:employee-id "emp-1" :name "田中花子" :jurisdiction :jp})

(def ^:private good-receipt
  {:receipt-id "rc-1" :employee-id "emp-1" :kind :invoice-received
   :registration-number "T1234567890123"
   :origin :paper :preservation :paper})

(def ^:private lines [{:purpose "会議費" :amount 5000}])

(defn- base [& {:as overrides}]
  (merge {:op :submit-claim :effect :propose :receipt "rc-1"
          :amount 5000 :lines lines :confidence 0.9}
         overrides))

(def ^:private cases
  "Each entry drives `check` into one disposition. Named, so a failure says
  which shape broke rather than which index."
  [{:name :clean
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base)}

   {:name :hard/no-employee
    :employee jp-employee :receipt good-receipt :request {:employee-id "ghost"}
    :proposal (base)}

   {:name :hard/no-actuation
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :effect :direct-payment)}

   {:name :hard/no-receipt
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :receipt nil)}

   {:name :hard/unknown-receipt
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :receipt "rc-999")}

   {:name :hard/receipt-wrong-employee
    :employee jp-employee
    :receipt (assoc good-receipt :employee-id "emp-2")
    :request {:employee-id "emp-1"}
    :proposal (base)}

   {:name :hard/amount-mismatch
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :amount 7000)}

   {:name :hard/unchecked-jurisdiction
    :employee (assoc jp-employee :jurisdiction :atlantis)
    :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit)}

   {:name :hard/invalid-registration-number
    :employee jp-employee
    :receipt (dissoc good-receipt :registration-number)
    :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit)}

   {:name :hard/electronic-record-not-preserved
    :employee jp-employee
    :receipt (assoc good-receipt :origin :electronic-transaction
                    :preservation :paper)
    :request {:employee-id "emp-1"}
    :proposal (base)}

   {:name :hard/invoice-not-preserved
    :employee jp-employee
    :receipt (assoc good-receipt :preservation :none)
    :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit)}

   ;; --- non-JP, since taxlaw@d2663b54 ------------------------------------
   ;; The three shapes the EU and US can produce. Included here rather than
   ;; only in `keihi.non-jp-test` because well-formedness is the property
   ;; that has to hold across the WHOLE disposition space, and a jurisdiction
   ;; that only appears in its own suite is a corner of that space nobody
   ;; checks for internal consistency.
   {:name :hard.us/unchecked-jurisdiction
    :employee (assoc jp-employee :jurisdiction [:us])
    :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit)}

   {:name :hard.eu/invoice-preservation-unread
    ;; the EU one. A well-formed VAT ID, so rule 8 passes and rule 7 never
    ;; fires — this case is held by rule 11 alone, which is exactly the
    ;; combination that committed before rule 11 existed.
    :employee (assoc jp-employee :jurisdiction [:eu])
    :receipt (assoc good-receipt :registration-number "DE123456789")
    :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit)}

   {:name :clean/eu-no-credit-claimed
    ;; and the EU claim that legitimately commits: no 仕入税額控除 sought, so
    ;; no unread precondition is reached. Rule 11 must not refuse ordinary
    ;; reimbursement abroad.
    :employee (assoc jp-employee :jurisdiction [:eu])
    :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base)}

   {:name :escalate/disburse
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal {:op :disburse :effect :propose :amount 5000 :confidence 0.99}}

   {:name :escalate/preservation-exception
    :employee jp-employee
    :receipt (assoc good-receipt :preservation :none)
    :request {:employee-id "emp-1"}
    :proposal (base :tax-treatment :input-tax-credit
                    :preservation-exception {:kind :disaster})}

   {:name :escalate/low-confidence
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (base :confidence 0.3)}

   {:name :escalate/no-confidence-key
    ;; a proposal that does not say how confident it is has not said it is
    ;; confident — the absent key must read as 0.0, never as trustworthy.
    :employee jp-employee :receipt good-receipt :request {:employee-id "emp-1"}
    :proposal (dissoc (base) :confidence)}])

(defn- verdict-for [{:keys [employee receipt request proposal]}]
  (let [st (store/mem-store)]
    (store/register-employee! st employee)
    (store/register-receipt! st receipt)
    (governor/check request {} proposal st)))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (testing "a HARD violation is never reported as escalatable"
    (doseq [{:keys [name] :as c} cases
            :let [v (verdict-for c)]
            :when (:hard? v)]
      (testing (str name)
        (is (not (:escalate? v))
            "an approver cannot be invited to wave through a HARD hold")
        (is (not (:ok? v)))
        (is (seq (:violations v)) "a hold must say what it refused")))))

(deftest escalation-carries-a-reason
  (testing "the console has something to show the approver"
    (doseq [{:keys [name] :as c} cases
            :let [v (verdict-for c)]
            :when (:escalate? v)]
      (testing (str name)
        (is (some? (:escalation-reason v)))))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; Evidence floor. A conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing — the failure mode
  ;; CLAUDE.md records as "measured nothing and reported clean".
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 2) "no clean case")
    (is (>= (count (filter :hard? vs)) 12) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 4) "escalation under-covered")
    (is (= (count cases) (count vs)))))

(deftest the-case-set-covers-every-jurisdiction-the-actor-can-be-asked-about
  ;; A second evidence floor, on the axis the taxlaw bump moved. Coverage
  ;; counts above are satisfied entirely by `[:jp]` cases, so they would not
  ;; notice the EU and US shapes falling out of this file.
  (let [js (into #{} (map (comp :jurisdiction :employee)) cases)]
    (is (contains? js :jp))
    (is (contains? js [:eu]))
    (is (contains? js [:us]))
    (is (contains? js :atlantis) "and one that is still not catalogued")))

(deftest every-named-hard-case-actually-raises-the-rule-it-is-named-for
  ;; Without this, a case named :hard/amount-mismatch that in fact trips
  ;; :no-receipt still counts toward the coverage floor above, and the floor
  ;; becomes decoration. The name is parsed, so adding a case cannot forget it.
  ;;
  ;; The namespace is matched by PREFIX rather than equality, so a rule can
  ;; be exercised in more than one jurisdiction without two cases colliding
  ;; on the same name: `:hard.us/unchecked-jurisdiction` and
  ;; `:hard/unchecked-jurisdiction` are distinct labels that both parse to
  ;; the same rule. The check itself is unchanged and still exact — widening
  ;; it to `contains?` on any rule is what would have made it decoration, and
  ;; it caught this very case being misnamed `:hard/…-us`.
  (doseq [{:keys [name] :as c} cases
          :when (str/starts-with? (or (namespace name) "") "hard")]
    (testing (str name)
      (let [rules (into #{} (map :rule) (:violations (verdict-for c)))]
        (is (contains? rules (keyword (clojure.core/name name)))
            (str "expected " (clojure.core/name name) ", got " (pr-str rules)))))))
