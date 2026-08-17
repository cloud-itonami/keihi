(ns keihi.tax-rules-test
  "The four rules that rest on a statute — three read out of
  `kotoba-lang/taxlaw`, one read out of the e-Gov law API into
  `keihi.shohizei`.

  Two things are being tested and they are not the same thing. The catalog's
  own behaviour is taxlaw's test suite's job. What has to live HERE is the
  wiring: that this actor asks about the right jurisdiction, that it splits a
  three-valued answer into distinct dispositions instead of collapsing it to a
  boolean, and that what could not be checked survives onto the verdict."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.shohizei :as shohizei]
            [keihi.governor :as governor]))

(defn- store-with [employee receipt]
  (let [st (store/mem-store)]
    (store/register-employee! st employee)
    (store/register-receipt! st receipt)
    st))

(def ^:private jp-employee
  {:employee-id "emp-1" :name "田中花子" :jurisdiction :jp})

(defn- claim [& {:as overrides}]
  (merge {:op :submit-claim :effect :propose :receipt "rc-1"
          :amount 5000 :lines [{:purpose "会議費" :amount 5000}]
          :confidence 0.95 :stake :low}
         overrides))

(defn- credit-claim [& {:as overrides}]
  (merge (claim) {:tax-treatment :input-tax-credit} overrides))

(defn- rules-of [v] (into #{} (map :rule) (:violations v)))

(defn- qualified-receipt [& {:as overrides}]
  (merge {:receipt-id "rc-1" :employee-id "emp-1" :kind :invoice-received
          :registration-number "T1234567890123"
          :origin :paper :preservation :paper}
         overrides))

;; ---------------------------------------------------------------------------
;; 7 + 8 — インボイス制度, via taxlaw/credit-support
;; ---------------------------------------------------------------------------

(deftest ok-on-a-jp-credit-claim-with-a-valid-registration-number
  (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                          (store-with jp-employee (qualified-receipt)))]
    (is (:ok? v))
    (is (true? (get-in v [:tax :credit :taxlaw/supported?])))))

(deftest hard-on-a-credit-claim-in-an-uncatalogued-jurisdiction
  (testing "an unchecked jurisdiction is a HOLD, not a pass"
    (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                            (store-with (assoc jp-employee :jurisdiction :atlantis)
                                        (qualified-receipt)))]
      (is (:hard? v))
      (is (contains? (rules-of v) :unchecked-jurisdiction)))))

(deftest hard-on-a-credit-claim-with-no-declared-jurisdiction
  (testing "an undeclared jurisdiction is unchecked, not domestic-by-default"
    (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                            (store-with (dissoc jp-employee :jurisdiction)
                                        (qualified-receipt)))]
      (is (:hard? v))
      (is (contains? (rules-of v) :unchecked-jurisdiction)))))

(deftest hard-on-a-receipt-silent-about-its-registration-number
  (testing "a receipt does not become creditable by being silent"
    (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                            (store-with jp-employee
                                        (dissoc (qualified-receipt)
                                                :registration-number)))
          detail (:detail (first (filter #(= :invalid-registration-number (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (re-find #"missing-registration-number" detail)
          "the library's reason is surfaced, so the two failures stay
           distinguishable to whoever reads the hold"))))

(deftest hard-on-a-malformed-registration-number
  (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                          (store-with jp-employee
                                      (qualified-receipt
                                       :registration-number "1234567890123")))
        detail (:detail (first (filter #(= :invalid-registration-number (:rule %))
                                       (:violations v))))]
    (is (:hard? v))
    (is (re-find #"malformed-registration-number" detail))))

(deftest the-jurisdiction-is-the-employees-not-the-proposals
  (testing "an advisor able to pick its own jurisdiction could pick one whose
            rules it satisfies"
    (let [v (governor/check {:employee-id "emp-1"} {}
                            (credit-claim :jurisdiction :jp)
                            (store-with (assoc jp-employee :jurisdiction :atlantis)
                                        (qualified-receipt)))]
      (is (:hard? v))
      (is (contains? (rules-of v) :unchecked-jurisdiction)
          "declaring :jp on the proposal must not rescue an :atlantis employee"))))

(deftest a-claim-with-no-credit-claimed-raises-no-invoice-question
  (testing "the actor does not invent a tax position in order to have one to
            check"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (dissoc (qualified-receipt)
                                                :registration-number)))]
      (is (:ok? v))
      (is (nil? (get-in v [:tax :credit])))
      (is (empty? (filter #{:unchecked-jurisdiction :invalid-registration-number}
                          (rules-of v)))))))

;; ---------------------------------------------------------------------------
;; 9 — 電子帳簿保存法 第七条, via taxlaw/record-preservation
;; ---------------------------------------------------------------------------

(deftest hard-on-an-electronic-transaction-kept-only-on-paper
  (testing "the obligation is to preserve the 電磁的記録 itself; printing it
            and keeping the paper is not that"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (qualified-receipt
                                         :origin :electronic-transaction
                                         :preservation :paper)))
          detail (:detail (first (filter #(= :electronic-record-not-preserved (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (re-find #"第七条" detail) "a refusal names the article it rests on"))))

(deftest the-article-7-rule-is-not-scoped-to-a-tax-claim
  (testing "第七条 binds the 保存義務者 whenever an 電子取引 happened, not only
            when a credit is claimed for it — unlike rules 7, 8 and 10"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (qualified-receipt
                                         :origin :electronic-transaction
                                         :preservation :paper)))]
      (is (contains? (rules-of v) :electronic-record-not-preserved))
      (is (not (contains? (rules-of v) :unchecked-jurisdiction))
          "no tax treatment was claimed, so rules 7 and 8 stay silent")
      (is (not (contains? (rules-of v) :invoice-not-preserved))
          "and so does rule 10"))))

(deftest ok-when-the-electronic-record-is-preserved-electronically
  (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                          (store-with jp-employee
                                      (qualified-receipt
                                       :origin :electronic-transaction
                                       :preservation :electronic)))]
    (is (:ok? v))
    (is (true? (get-in v [:tax :preservation :taxlaw/preserved?])))))

(deftest a-receipt-declaring-no-origin-is-not-held
  (testing "it asserted nothing about how the transaction happened"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (dissoc (qualified-receipt) :origin)))]
      (is (:ok? v))
      (is (not (contains? (rules-of v) :electronic-record-not-preserved))))))

(deftest a-receipt-declaring-no-origin-is-not-a-SILENT-pass
  (testing "otherwise `we looked and it was fine` and `nobody looked` are the
            same output"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (dissoc (qualified-receipt) :origin)))]
      (is (= :not-declared (get-in v [:tax :preservation :taxlaw/coverage])))
      (is (not (contains? (get-in v [:tax :preservation]) :taxlaw/preserved?))
          "the pass-shaped key must be ABSENT, not false"))))

;; ---------------------------------------------------------------------------
;; 10 — 消費税法 第三十条第七項, read here (keihi.shohizei)
;; ---------------------------------------------------------------------------

(deftest hard-on-a-credit-claim-whose-receipt-is-not-preserved
  (testing "仕入税額控除は帳簿及び請求書等を保存しない場合には適用しない"
    (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                            (store-with jp-employee
                                        (qualified-receipt :preservation :none)))
          detail (:detail (first (filter #(= :invoice-not-preserved (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (re-find #"第三十条第七項" detail))
      (is (re-find #"invoice-not-preserved" detail)))))

(deftest hard-on-a-credit-claim-whose-preservation-was-never-recorded
  (testing "the 本文 disallows the credit ABSENT preservation and the ただし書
            puts the burden of proof on the business, so an unrecorded state
            is on the refusing side of this article"
    (let [v (governor/check {:employee-id "emp-1"} {} (credit-claim)
                            (store-with jp-employee
                                        (dissoc (qualified-receipt) :preservation)))
          detail (:detail (first (filter #(= :invoice-not-preserved (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (re-find #"preservation-not-recorded" detail)
          "and the two refusals stay distinguishable"))))

(deftest the-article-30-rule-is-scoped-to-the-claim
  (testing "第三十条 is about the credit; a claim not seeking one raises no
            question under it, and the verdict says :no-claim rather than
            reporting a pass"
    (let [v (governor/check {:employee-id "emp-1"} {} (claim)
                            (store-with jp-employee
                                        (qualified-receipt :preservation :none)))]
      (is (:ok? v))
      (is (not (contains? (rules-of v) :invoice-not-preserved)))
      (is (= :no-claim (get-in v [:tax :invoice-preservation :keihi/coverage])))
      (is (not (contains? (get-in v [:tax :invoice-preservation])
                          :keihi/preserved?))))))

(deftest the-article-30-rule-only-fires-where-the-statute-was-read
  (testing "taxlaw covering a jurisdiction means someone read ITS rules, not
            this one — so a future non-JP entry must not switch 消費税法 on"
    (is (= #{[:jp]} shohizei/read-jurisdictions))
    (let [r (shohizei/preservation-of-invoice
             [:us :ca] {:preservation :none} {:tax-treatment :input-tax-credit})]
      (is (= :none (:keihi/coverage r)))
      (is (not (contains? r :keihi/preserved?))
          "an unread jurisdiction yields no answer at all, not a false one"))))

(deftest the-proviso-escalates-rather-than-passing-or-refusing
  (testing "災害その他やむを得ない事情 must be 証明 by the business. A governor
            cannot verify a flood; refusing outright would enforce a rule the
            statute does not contain, and passing would enforce nothing"
    (let [v (governor/check
             {:employee-id "emp-1"} {}
             (credit-claim :preservation-exception
                           {:kind :disaster :note "2026-08 の水害で焼失"})
             (store-with jp-employee (qualified-receipt :preservation :none)))]
      (is (not (:hard? v)) "the ただし書 is a real clause of the article")
      (is (:escalate? v))
      (is (not (contains? (rules-of v) :invoice-not-preserved)))
      (is (true? (get-in v [:tax :invoice-preservation :keihi/exception-asserted?])))
      (is (false? (get-in v [:tax :invoice-preservation :keihi/preserved?]))
          "the exception does not make the document preserved — it asks a
           human whether the proof holds"))))

(deftest the-proviso-lifts-only-its-own-article
  (testing "第七条 has no such proviso, and asserting one under 第三十条 must
            not reach it — nor any other invariant"
    (let [v (governor/check
             {:employee-id "emp-1"} {}
             (credit-claim :preservation-exception {:kind :disaster})
             (store-with jp-employee
                         (qualified-receipt :origin :electronic-transaction
                                            :preservation :none
                                            :registration-number nil)))]
      (is (:hard? v))
      (is (contains? (rules-of v) :electronic-record-not-preserved))
      (is (contains? (rules-of v) :invalid-registration-number))
      (is (not (contains? (rules-of v) :invoice-not-preserved))))))

(deftest an-asserted-exception-in-an-unread-jurisdiction-invents-no-escalation
  (testing "the escalation is guarded on the article having been REACHED"
    (let [v (governor/check
             {:employee-id "emp-1"} {}
             (claim :preservation-exception {:kind :disaster})
             (store-with (assoc jp-employee :jurisdiction :atlantis)
                         (qualified-receipt :preservation :none)))]
      (is (:ok? v))
      (is (not (:escalate? v))))))

;; ---------------------------------------------------------------------------
;; the statute this repo enforces on its own authority
;; ---------------------------------------------------------------------------

(deftest the-quoted-article-carries-its-provenance
  (testing "a rule enforced on the strength of a URL nobody opened is not a
            cited rule"
    (let [p shohizei/provision]
      (is (= "363AC0000000108" (:law/id p)))
      (is (re-find #"law_data/363AC0000000108" (:retrieved-via p)))
      (is (re-find #"適用しない" (:quote p)) "the 本文 disallows")
      (is (re-find #"この限りでない" (:proviso p))
          "and the proviso is kept, because dropping it would turn a
           rebuttable disallowance into an absolute one"))))
