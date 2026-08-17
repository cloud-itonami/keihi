(ns keihi.governor
  "KeihiGovernor — the independent safety / traceability layer for 経費.
  Wired as its own `:govern` node in `keihi.actor`'s StateGraph, downstream of
  `:advise`. The KeihiAdvisor has no notion of employee provenance, receipt
  ownership, claim arithmetic or 消費税法, so this MUST be a separate system
  able to reject a proposal (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on `cloud-itonami-isco-4311`'s
  bookkeeping.governor.

  HARD invariants (`:hard?` true, ALWAYS `:hold`, never approvable past):

    1. employee provenance  — the request's employee must be registered.
    2. no-actuation         — the proposal's `:effect` must be `:propose`.
    3. receipt basis        — a claim must CITE a receipt. A reimbursement
                              with no receipt is an invented expense.
    4. known receipt        — the cited receipt must exist.
    5. receipt ownership    — it must belong to the claiming employee.
                              Without this, knowing a receipt id is enough to
                              be paid for someone else's dinner.
    6. claim arithmetic     — `:amount` must equal the sum of `:lines`. An
                              approver cannot approve their way past a total
                              that does not add up; whichever number is wrong,
                              nobody knows which, and paying either is paying
                              a figure no document supports.
    7. checked jurisdiction — a claim asserting 仕入税額控除 whose employee's
                              jurisdiction taxlaw does not catalogue is HELD.
                              An unchecked jurisdiction is a hold, not a pass.
    8. qualified invoice    — where the jurisdiction conditions that credit on
                              a 適格請求書, the cited receipt must carry a
                              registration number in that format. A receipt
                              silent about its 登録番号 does not become
                              creditable by being silent.
    9. 電磁的記録の保存      — a receipt from an 電子取引 kept only on paper is
                              HELD, citing 電子帳簿保存法 第七条. NOT scoped to
                              a tax claim: the article binds the 保存義務者
                              whenever an 電子取引 happened.
   10. 請求書等の保存        — a 仕入税額控除 claim whose receipt is not
                              preserved (or whose preservation was never
                              recorded) is HELD, citing 消費税法 第三十条第七項.
                              Scoped to the claim, because the article is.

  ESCALATION invariants (`:escalate?` true, human sign-off, the operation is
  legitimate):

   11. `:op :disburse`      — real money leaves the company. The one operation
                              here whose effect is irreversible outside this
                              system's own storage.
   12. 保存の例外の主張      — the claim invokes 第三十条第七項's ただし書
                              (災害その他やむを得ない事情). The article requires
                              the BUSINESS to 証明 it; a governor cannot verify
                              a flood, and refusing outright would enforce a
                              rule the statute does not contain. So it neither
                              swallows nor honours the assertion — it hands it
                              to someone who can look at the proof.
   13. low confidence       — below `confidence-floor`.

  ## Where each rule comes from, and why that matters

  Rules 1, 2, 4 and 5 are not expense rules at all — every actor in this fleet
  has them, and they were hand-copied into 376 governors, one of which
  silently drifted into reporting a HARD violation as escalatable
  (`kotoba-lang/governor`'s README measures it). They come from that library:

    :no-employee            gov/missing-subject
    :no-actuation           gov/no-actuation
    :unknown-receipt        gov/unknown-scope
    :receipt-wrong-employee gov/scope-owner-mismatch

  So does the verdict assembly, which is where the drift happened.

  Rules 7, 8 and 9 are not this actor's either — they are `kotoba-lang/taxlaw`
  answering, and this namespace's whole job for them is to ask about the right
  jurisdiction and to split a three-valued answer into distinct dispositions.

  Rule 10 IS this repo's, because taxlaw does not answer it and is honest
  about not answering it (`:rule/review :reachable-not-read` on the credit
  rule). Its statutory text was read before it was enforced; see
  `keihi.shohizei`.

  ## Unknown is never a pass, and the verdict says which unknown

  Every three-valued answer above is split three ways here, never two, and
  what could NOT be checked rides on the verdict under `:tax` rather than
  being swallowed. A console that shows `we looked and it was fine` and
  `nobody looked` as the same green tick has lost the only distinction these
  libraries exist to preserve."
  (:require [keihi.store :as store]
            [keihi.shohizei :as shohizei]
            [kotoba.taxlaw :as taxlaw]
            [governor.core :as gov]))

(def confidence-floor
  "0.6 — what 365 of the 376 surveyed actors use. The outliers are deliberate
  and this is not one of them."
  0.6)

(def ^:private escalating-ops
  "Disbursement moves real money out of the company. Every other op here
  writes a record this system can amend."
  #{:disburse})

(def ^:private claim-ops
  "The ops that assert an expense and therefore need a receipt behind them.
  `:disburse` and `:reject-claim` act on a claim already submitted, so
  requiring them to re-cite the receipt would be theatre — the citation was
  checked when the claim was submitted."
  #{:submit-claim :amend-claim})

(defn- lines-total [lines]
  (transduce (map :amount) + 0 lines))

(defn- claims-input-tax-credit? [proposal]
  (= :input-tax-credit (:tax-treatment proposal)))

(defn- tax-report
  "What the three statutes could and could not say about this proposal.

  Returned whole so it can ride on the verdict AND drive the rules, rather
  than being computed twice and drifting between the two — which is how a
  console ends up disagreeing with the hold it is displaying.

  `nil` when there is nothing to assess: no receipt was read, or the op is not
  one that asserts an expense. `:reject-claim` is the case that makes this
  load-bearing — a claim whose receipt fails 電帳法 第七条 is precisely the one
  someone needs to be able to reject, and a governor that held the rejection
  would trap it."
  [jurisdiction receipt-record proposal claim?]
  (when (and claim? receipt-record)
    {:credit (when (claims-input-tax-credit? proposal)
               (taxlaw/credit-support jurisdiction receipt-record))
     :preservation (taxlaw/record-preservation jurisdiction receipt-record)
     :invoice-preservation (shohizei/preservation-of-invoice
                            jurisdiction receipt-record proposal)}))

(defn- hard-violations
  [{:keys [request proposal]} employee-record receipt-record tax]
  (let [{:keys [op amount lines]} proposal
        claim? (contains? claim-ops op)
        {:keys [credit preservation invoice-preservation]} tax]
    (gov/violations
     ;; --- the fleet's four, from kotoba-lang/governor ---------------------
     (gov/missing-subject employee-record {:rule :no-employee
                                           :detail "未登録の従業員に対する操作は不可"})
     (gov/no-actuation proposal
                       {:detail (str "effect は :propose のみ許可"
                                     "（governor を経ない直接の書込・支払は行わない）")})
     ;; `:no-receipt` and `:unknown-receipt` stay distinct on purpose: citing
     ;; nothing and citing something that does not exist are different
     ;; failures, and only the second is `unknown-scope`. So this applies only
     ;; once a citation was actually made.
     (gov/unknown-scope receipt-record
                        {:applies? (boolean (and claim? (:receipt proposal)))
                         :rule :unknown-receipt
                         :detail (str "未登録の証憑: " (pr-str (:receipt proposal)))})
     ;; ownership is carried as :employee-id on BOTH sides here, but naming it
     ;; explicitly is what stops a later rename on one side from silently
     ;; comparing nil to nil and passing.
     (when claim?
       (gov/scope-owner-mismatch receipt-record request
                                 {:owner-key :employee-id
                                  :rule :receipt-wrong-employee
                                  :detail "証憑が別の従業員のもの"}))

     ;; --- 経費's own --------------------------------------------------------
     (cond-> []
       (and claim? (nil? (:receipt proposal)))
       (conj {:rule :no-receipt
              :detail "経費精算には領収書・請求書の引用が必須（架空経費の禁止）"})

       ;; 6. arithmetic. `amount` is compared as given, NOT defaulted to 0: a
       ;; claim that forgot its total reads as `nil ≠ 5000` and is reported,
       ;; where a default would have made a totalless claim over empty lines
       ;; into a coincidental 0 = 0 pass.
       (and claim? (not= amount (lines-total lines)))
       (conj {:rule :amount-mismatch
              :detail (str "請求額 " (pr-str amount) " ≠ 明細合計 "
                           (lines-total lines)
                           "（どちらが誤りか判らない以上、いずれを支払っても"
                           "根拠のない金額を支払うことになる）")})

       ;; 7. the credit is claimed in a jurisdiction nobody has catalogued.
       (= :none (:taxlaw/coverage credit))
       (conj {:rule :unchecked-jurisdiction
              :detail (str "仕入税額控除を主張しているが、法域 "
                           (pr-str (:jurisdiction employee-record))
                           " は kotoba.taxlaw に無い（未検査は合格ではない）")})

       ;; 8. catalogued, and this receipt does not support the credit under it.
       (and (= :checked (:taxlaw/coverage credit))
            (false? (:taxlaw/supported? credit)))
       (conj {:rule :invalid-registration-number
              :detail (str "仕入税額控除には適格請求書発行事業者の登録番号が要る（"
                           (name (:taxlaw/reason credit)) "）。証憑 "
                           (pr-str (:receipt proposal)) " の登録番号: "
                           (pr-str (:registration-number receipt-record)))})

       ;; 9. 電子帳簿保存法 第七条 — an 電子取引 kept only on paper.
       (and (= :checked (:taxlaw/coverage preservation))
            (false? (:taxlaw/preserved? preservation)))
       (conj {:rule :electronic-record-not-preserved
              :detail (str (:taxlaw/provision preservation)
                           "（" (name (:taxlaw/reason preservation)) "）: 証憑 "
                           (pr-str (:receipt proposal))
                           " は電子取引だが電磁的記録として保存されていない: "
                           (pr-str (:preservation receipt-record)))})

       ;; 10. 消費税法 第三十条第七項 — the 請求書等 is not preserved. An
       ;; asserted ただし書 exception is NOT excluded here by weakening the
       ;; rule; it is excluded by this clause, so the exception can never
       ;; reach rules 7-9 or any other invariant.
       (and (= :checked (:keihi/coverage invoice-preservation))
            (false? (:keihi/preserved? invoice-preservation))
            (not (:keihi/exception-asserted? invoice-preservation)))
       (conj {:rule :invoice-not-preserved
              :detail (str (:keihi/provision invoice-preservation)
                           "（" (name (:keihi/reason invoice-preservation))
                           "）: 仕入税額控除は帳簿及び請求書等を保存しない場合には"
                           "適用しない。証憑 " (pr-str (:receipt proposal))
                           " の保存状態: "
                           (pr-str (:keihi/preservation invoice-preservation)))})))))

(defn- exception-asserted?
  "The ただし書 was invoked and the article was actually reached. Guarded on
  `:checked` so that asserting an exception in a jurisdiction whose statute
  was never read cannot manufacture an escalation out of nothing."
  [tax]
  (boolean (:keihi/exception-asserted? (:invoice-preservation tax))))

(defn check
  "Assess a proposal against `request` / `context` / `proposal` and a `store`
  implementing `keihi.store/Store`. Pure — never mutates the store.

  Returns
  `{:ok? :violations :confidence :hard? :escalate? :escalation-reason :tax}`."
  [request _context proposal store]
  (let [employee-record (store/employee store (:employee-id request))
        receipt-record (some->> (:receipt proposal) (store/receipt store))
        ;; the jurisdiction is the EMPLOYEE's, not the proposal's — an advisor
        ;; able to pick its own jurisdiction could pick one whose rules it
        ;; satisfies.
        jurisdiction (:jurisdiction employee-record)
        tax (tax-report jurisdiction receipt-record proposal
                        (contains? claim-ops (:op proposal)))
        hard (hard-violations {:request request :proposal proposal}
                              employee-record receipt-record tax)]
    (gov/verdict
     {:violations hard
      :confidence (:confidence proposal)
      :escalating-op? (or (contains? escalating-ops (:op proposal))
                          (exception-asserted? tax))
      :confidence-floor confidence-floor
      ;; What the statutes were and were NOT able to say. A receipt that
      ;; declares no `:origin` is not held — it has asserted nothing — but the
      ;; verdict says so rather than letting `nobody looked` and `we looked and
      ;; it was fine` produce the same output. Same device as isco-4311's
      ;; `:tax` and kintai's `:unevaluated`.
      :extra {:tax tax}})))
