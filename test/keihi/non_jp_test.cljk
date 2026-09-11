(ns keihi.non-jp-test
  "What this actor says about an expense claim outside Japan.

  `kotoba-lang/taxlaw` gained `[:eu]` and `[:us]` at `d2663b54`, and coverage
  there became per FACET rather than per jurisdiction. Before that, every
  non-JP credit claim was held by one rule for one reason, and this actor had
  never been asked a question it could half-answer.

  Three properties are under test, and they pull in different directions:

  1. **Nothing widened.** A jurisdiction this actor cannot check is refused
     exactly as hard as it was before that jurisdiction existed in the
     catalog. Measured against `[:atlantis]`, live, rather than against a
     remembered constant.
  2. **What it CAN check, it checks** — the EU really does have an invoice
     rule, and a receipt with no VAT ID is refused on it.
  3. **What it cannot check, it says.** Not by omission: by a named limit on
     the verdict, because a `:checked` with nothing beside it reads as
     `valid` and a `:none` with nothing beside it reads as nothing at all."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.governor :as governor]
            [keihi.edge.endpoints :as edge]
            [kotoba.taxlaw :as taxlaw]))

(defn- store-with [employee receipt]
  (let [st (store/mem-store)]
    (store/register-employee! st employee)
    (store/register-receipt! st receipt)
    st))

(def ^:private jp-registration-number
  "適格請求書発行事業者の登録番号 shape: T + 13 digits. Perfectly valid in Japan
  and meaningless everywhere else — which is the point of every test below
  that uses it."
  "T1234567890123")

(defn- receipt [& {:as overrides}]
  (merge {:receipt-id "rc-1" :employee-id "emp-1" :kind :invoice-received
          :registration-number jp-registration-number
          :origin :paper :preservation :paper}
         overrides))

(defn- credit-claim [& {:as overrides}]
  (merge {:op :submit-claim :effect :propose :receipt "rc-1"
          :amount 5000 :lines [{:purpose "会議費" :amount 5000}]
          :confidence 0.95 :stake :low
          :tax-treatment :input-tax-credit}
         overrides))

(defn- verdict-in
  "One governor verdict for an employee registered in `j`."
  [j & {:keys [receipt-overrides claim-overrides]}]
  (governor/check {:employee-id "emp-1"} {}
                  (merge (credit-claim) claim-overrides)
                  (store-with {:employee-id "emp-1" :name "田中花子"
                               :jurisdiction j}
                              (merge (receipt) receipt-overrides))))

(defn- rules-of [v] (into #{} (map :rule) (:violations v)))

(defn- limit-of [v k]
  (first (filter #(= k (:keihi/limit %)) (get-in v [:tax :limits]))))

(defn- detail-of [v rule]
  (:detail (first (filter #(= rule (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; 1 — nothing widened
;; ---------------------------------------------------------------------------

(deftest a-us-claim-is-refused-exactly-as-hard-as-an-uncatalogued-one
  (testing "the United States entering the catalog must not make a claim
            there creditable. The comparison is against a jurisdiction that
            is STILL absent, computed in the same run, so this cannot pass by
            agreeing with a stale remembered constant"
    (let [us (verdict-in [:us])
          nowhere (verdict-in [:atlantis])]
      (is (:hard? us))
      (is (false? (:ok? us)))
      (is (not (:escalate? us))
          "a HARD hold is never offered to an approver")
      (is (= (rules-of nowhere) (rules-of us))
          "the same refusal, rule for rule")
      (is (= {:hard? true :ok? false :escalate? false}
             (select-keys us [:hard? :ok? :escalate?])
             (select-keys nowhere [:hard? :ok? :escalate?]))))))

(deftest a-japanese-registration-number-does-not-travel
  (testing "the receipt cited here carries a perfectly good 登録番号. It is
            evidence about Japan, and the claim is not in Japan"
    (let [us (verdict-in [:us])]
      (is (= jp-registration-number
             (:registration-number (store/receipt
                                    (store-with {:employee-id "emp-1"
                                                 :jurisdiction [:us]}
                                                (receipt))
                                    "rc-1")))
          "the number really is on the document")
      (is (:hard? us) "and it buys the claim nothing")
      (is (contains? (rules-of us) :unchecked-jurisdiction)))))

(deftest the-us-refusal-is-in-the-catalog-and-still-a-refusal
  (testing "`covered?` flipped to true for [:us] at d2663b54 while the
            input-tax-credit facet stayed out of scope. A governor that had
            gated on `covered?` would have started passing here"
    (is (true? (taxlaw/covered? [:us])) "the jurisdiction IS catalogued")
    (is (nil? (taxlaw/facet-of [:us] :jurisdiction/input-tax-credit))
        "and the facet this actor needs is not")
    (is (:hard? (verdict-in [:us])))))

;; ---------------------------------------------------------------------------
;; 2 — the reason travels with the refusal
;; ---------------------------------------------------------------------------

(deftest the-us-hold-names-why-there-is-no-rule-rather-than-refusing-bare
  (testing "the operator is told that there is no federal VAT — not merely
            that the answer is no. Before the bump this detail said the
            jurisdiction was `kotoba.taxlaw に無い`, which is now false for
            precisely the jurisdiction it would be printed for"
    (let [detail (detail-of (verdict-in [:us]) :unchecked-jurisdiction)]
      (is (re-find #"no federal VAT" detail))
      (is (not (re-find #"kotoba\.taxlaw に無い" detail))
          "the old sentence would be a lie about a catalogued jurisdiction"))))

(deftest an-uncatalogued-jurisdiction-still-says-it-is-uncatalogued
  (testing "and the two refusals do not collapse into one another — taxlaw
            offers no reason for [:atlantis] because nobody considered it"
    (let [detail (detail-of (verdict-in [:atlantis]) :unchecked-jurisdiction)]
      (is (re-find #"kotoba\.taxlaw に無い" detail))
      (is (not (re-find #"federal VAT" detail))))))

;; ---------------------------------------------------------------------------
;; 3 — the EU: it can check something, and must not overstate what
;; ---------------------------------------------------------------------------

(deftest an-eu-claim-really-is-checked-against-an-invoice-rule
  (testing "Article 226(3) requires the supplier's VAT identification number,
            so this is a real check and not a pass-through"
    (let [v (verdict-in [:eu] :receipt-overrides {:registration-number nil})]
      (is (:hard? v))
      (is (contains? (rules-of v) :invalid-registration-number))
      (is (= :checked (get-in v [:tax :credit :taxlaw/coverage]))
          "the EU facet was READ; this is not the unchecked-jurisdiction path")
      (is (re-find #"missing-registration-number"
                   (detail-of v :invalid-registration-number))))))

(deftest a-japanese-number-fails-the-eu-format-too
  (testing "T1234567890123 has no ISO 3166 alpha-2 prefix"
    (let [v (verdict-in [:eu])]
      (is (contains? (rules-of v) :invalid-registration-number))
      (is (re-find #"malformed-registration-number"
                   (detail-of v :invalid-registration-number))))))

(deftest a-true-from-the-eu-format-check-is-reported-as-a-prefix-and-nothing-else
  (testing "Article 215 gives the prefix; the body is Member State law nobody
            read. Reporting `:checked` alone would say the VAT number is
            valid, which is strictly more than was measured"
    (let [v (verdict-in [:eu] :receipt-overrides {:registration-number "DE123456789"})
          lim (limit-of v :registration-format-partial)]
      (is (true? (get-in v [:tax :credit :taxlaw/supported?]))
          "the format check passed")
      (is (some? lim) "and the verdict says what that did not establish")
      (is (= [:prefix-shape] (:keihi/checked lim)))
      (is (= [:body-format :check-digit :member-state-is-a-member]
             (:keihi/not-checked lim))
          "named individually, so a reader cannot infer the set from a count")
      (is (re-find #"番号が有効であることの確認ではない" (:keihi/detail lim))))))

(deftest japan-carries-no-format-caveat-because-there-is-nothing-uncheckable
  (testing "the caveat is not decoration applied to every jurisdiction — T +
            13 digits is the whole of the published format, so a `true` there
            really does mean the format was satisfied"
    (let [v (verdict-in [:jp])]
      (is (true? (get-in v [:tax :credit :taxlaw/supported?])))
      (is (nil? (limit-of v :registration-format-partial))))))

;; ---------------------------------------------------------------------------
;; 4 — the EU credit is still held, on the precondition nobody read
;; ---------------------------------------------------------------------------

(deftest an-eu-credit-claim-with-a-well-formed-vat-id-is-still-held
  (testing "MEASURED 2026-08-18: at taxlaw@d2663b54 and before rule 11, this
            exact claim went from HELD to COMMITTED. taxlaw supplied the
            invoice rule, so rule 7 fell silent, and 消費税法 第三十条第七項's
            precondition — that the 請求書等 be preserved — had never been
            read for the EU and nothing else was watching it"
    (let [v (verdict-in [:eu] :receipt-overrides {:registration-number "DE123456789"})]
      (is (:hard? v))
      (is (false? (:ok? v)))
      (is (= #{:invoice-preservation-unread} (rules-of v))
          "and by this rule alone — rule 7 correctly has nothing to say")
      (is (re-find #"第三十条第七項" (detail-of v :invoice-preservation-unread))
          "the hold names the article whose precondition is unread"))))

(deftest rule-11-fires-on-unread-and-rule-10-on-unpreserved-and-they-are-not-the-same
  (testing "`nobody read the rule here` and `the rule was read and this
            document fails it` are different facts about a claim, and an
            operator can act on only one of them"
    (let [unread (verdict-in [:eu] :receipt-overrides {:registration-number "DE123456789"})
          failed (verdict-in [:jp] :receipt-overrides {:preservation :none})]
      (is (contains? (rules-of unread) :invoice-preservation-unread))
      (is (not (contains? (rules-of unread) :invoice-not-preserved)))
      (is (contains? (rules-of failed) :invoice-not-preserved))
      (is (not (contains? (rules-of failed) :invoice-preservation-unread))))))

(deftest a-non-credit-claim-outside-japan-raises-no-unread-precondition
  (testing "第三十条 is about the credit. A claim that seeks none asks nothing
            under it, so rule 11 must not fire merely because the employee is
            abroad — that would refuse ordinary reimbursement everywhere"
    (let [v (verdict-in [:eu] :claim-overrides {:tax-treatment nil})]
      (is (:ok? v))
      (is (empty? (rules-of v)))
      (is (= :no-claim (get-in v [:tax :invoice-preservation :keihi/coverage]))))))

(deftest an-unread-precondition-cannot-be-escalated-past
  (testing "the ただし書 requires the article to have been REACHED. Asserting
            a disaster in a jurisdiction where 第三十条第七項 was never read
            must not manufacture an approvable route around rule 11"
    (let [v (verdict-in [:eu]
                        :receipt-overrides {:registration-number "DE123456789"
                                            :preservation :none}
                        :claim-overrides {:preservation-exception {:kind :disaster}})]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (contains? (rules-of v) :invoice-preservation-unread))
      (is (not (true? (get-in v [:tax :invoice-preservation
                                 :keihi/exception-asserted?])))))))

;; ---------------------------------------------------------------------------
;; 5 — the electronic-record asymmetry
;; ---------------------------------------------------------------------------

(deftest an-eu-electronic-receipt-kept-on-paper-is-not-reported-as-preserved
  (testing "電帳法 第七条 binds the HOLDER to preserve; Articles 218/246 bind
            the MEMBER STATE to accept. Same facet key, opposite direction —
            so Japan's answer must not be inherited in either direction"
    (let [v (verdict-in [:eu] :claim-overrides {:tax-treatment nil}
                        :receipt-overrides {:origin :electronic-transaction
                                            :preservation :paper})]
      (is (not (true? (get-in v [:tax :preservation :taxlaw/preserved?])))
          "the pass-shaped key must never be true here")
      (is (= :none (get-in v [:tax :preservation :taxlaw/coverage]))))))

(deftest and-it-is-not-held-either-because-the-directive-does-not-say-that
  (testing "holding would enforce a rule the Directive does not contain,
            which is the mistake the ただし書 handling refuses in the other
            direction. The limit is what carries the fact instead"
    (let [v (verdict-in [:eu] :claim-overrides {:tax-treatment nil}
                        :receipt-overrides {:origin :electronic-transaction
                                            :preservation :paper})
          lim (limit-of v :electronic-preservation-unread)]
      (is (:ok? v))
      (is (not (contains? (rules-of v) :electronic-record-not-preserved)))
      (is (some? lim) "silence alone would read as `preserved`")
      (is (re-find #"Member State" (:keihi/why lim))
          "and it carries the Directive's own reason, not a generic one"))))

(deftest japan-still-holds-the-same-receipt
  (testing "the asymmetry is real in both directions, asserted in one run so
            neither side can drift alone"
    (let [jp (verdict-in [:jp] :claim-overrides {:tax-treatment nil}
                         :receipt-overrides {:origin :electronic-transaction
                                             :preservation :paper})]
      (is (:hard? jp))
      (is (contains? (rules-of jp) :electronic-record-not-preserved))
      (is (true? (taxlaw/requires-electronic-record? [:jp])))
      (is (nil? (taxlaw/requires-electronic-record? [:eu]))
          "nil, not false — the Directive did not answer this question"))))

;; ---------------------------------------------------------------------------
;; 6 — retention: nil is an answer, and 7 is not the whole answer
;; ---------------------------------------------------------------------------

(deftest retention-nil-outside-japan-is-the-instruments-answer-not-missing-data
  (testing "Art 247(1) hands the period to the Member State; § 1.6001-1(e)
            states a condition rather than a number. A caller must be able to
            tell either from `nobody read anything`"
    (let [eu (get-in (verdict-in [:eu]) [:tax :retention])
          us (get-in (verdict-in [:us]) [:tax :retention])
          nowhere (get-in (verdict-in [:atlantis]) [:tax :retention])]
      (is (nil? (taxlaw/retention-years [:eu])))
      (is (nil? (taxlaw/retention-years [:us])))
      (is (nil? (taxlaw/retention-years [:atlantis])) "all three nil in taxlaw")

      (is (= :no-period (:keihi/retention eu)))
      (is (= :member-state (:keihi/period-set-by eu)))
      (is (re-find #"247" (:keihi/provision eu)))

      (is (= :no-period (:keihi/retention us)))
      (is (= :materiality (:keihi/period-set-by us)))
      (is (re-find #"1\.6001-1" (:keihi/provision us)))

      (is (= :unread (:keihi/retention nowhere))
          "and THIS one really is missing data")
      (is (nil? (:keihi/period-set-by nowhere)))
      (is (not= (:keihi/retention eu) (:keihi/retention nowhere))
          "the two nils must not render as the same blank"))))

(deftest this-actor-never-hands-anyone-a-bare-number-of-years
  (testing "not even in Japan. 第五十九条 binds 青色申告法人, the clock starts
            at 起算日 and not at the receipt, and 第二十六条の三 makes it ten
            where 欠損金の繰越し is relied on"
    (let [jp (get-in (verdict-in [:jp]) [:tax :retention])]
      (is (= :years (:keihi/retention jp)))
      (is (= 7 (:keihi/years jp)))
      (is (= 10 (:keihi/years-with-loss-carryforward jp))
          "seven alone would be wrong for a company carrying losses forward")
      (is (= :blue-return-corporation (:keihi/binds jp))
          "and wrong for a company that is not 青色申告")
      (is (re-find #"起算日" (:keihi/why jp))
          "and counted from the wrong day even when the number is right"))))

;; ---------------------------------------------------------------------------
;; 7 — the HTTP surface says all of it
;; ---------------------------------------------------------------------------

(deftest the-claim-response-carries-the-limits-and-the-retention-answer
  (testing "`tax-coverage` reduces each statute to one keyword, and one
            keyword cannot distinguish `the format matched` from `the format
            is all we could look at`. The limits ride alongside"
    (let [eu (edge/tax-coverage
              (verdict-in [:eu] :receipt-overrides {:registration-number "DE123456789"}))
          jp (edge/tax-coverage (verdict-in [:jp]))]
      (is (= :checked (:credit eu)))
      (is (= :checked (:credit jp))
          "the same keyword for two very different amounts of knowledge")
      (is (= [:registration-format-partial :electronic-preservation-unread]
             (mapv :keihi/limit (:limits eu)))
          "which is why the response cannot stop at that keyword")
      (is (= [] (:limits jp)))
      (is (= :no-period (get-in eu [:retention :keihi/retention])))
      (is (= :years (get-in jp [:retention :keihi/retention]))))))

(deftest a-verdict-with-no-tax-report-still-produces-a-well-formed-coverage-map
  (testing "the reducer runs over every ledger entry, including ones where
            nothing was assessed — an exception there would take out the
            ledger route rather than one claim"
    (let [c (edge/tax-coverage {})]
      (is (false? (:assessed c)))
      (is (= [] (:limits c)))
      (is (nil? (:retention c))))))
