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
   11. 保存要件が未読        — a 仕入税額控除 claim in a jurisdiction whose
                              preservation precondition for the credit was
                              never READ is HELD. Rule 10's unread arm, and
                              the one this actor needed most recently; see
                              the section below.

  ESCALATION invariants (`:escalate?` true, human sign-off, the operation is
  legitimate):

   12. `:op :disburse`      — real money leaves the company. The one operation
                              here whose effect is irreversible outside this
                              system's own storage.
   13. 保存の例外の主張      — the claim invokes 第三十条第七項's ただし書
                              (災害その他やむを得ない事情). The article requires
                              the BUSINESS to 証明 it; a governor cannot verify
                              a flood, and refusing outright would enforce a
                              rule the statute does not contain. So it neither
                              swallows nor honours the assertion — it hands it
                              to someone who can look at the proof.
   14. low confidence       — below `confidence-floor`.

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

  ## What this actor can and cannot say outside Japan

  Measured 2026-08-18 against `taxlaw@d2663b54`, which added `[:eu]` and
  `[:us]` and made coverage per FACET rather than per jurisdiction. A
  jurisdiction being in the catalog no longer means the catalog answers the
  question you are asking about it, and this actor asks four separate ones.

  | | `[:jp]` | `[:eu]` | `[:us]` |
  |---|---|---|---|
  | 適格請求書 / VAT-ID on the invoice | 登録番号 `T`+13桁, checked | Art 226(3) requires the supplier's VAT ID; **prefix only**, see below | **no federal analogue** — out of scope |
  | 請求書等の保存 as a condition of the credit | 消費税法 第三十条第七項, READ | **not read** | **not read** |
  | must the HOLDER preserve an 電磁的記録 as such | 電帳法 第七条, yes | **the Directive does not say** | not read |
  | how long to keep it | 7 years, **qualified** | the Directive states **no number** | the regulation states **no number** |

  ### `[:eu]` — it can check something, and must not overstate what

  Article 226 is a closed list and (3) is the supplier's VAT identification
  number, so `credit-support` comes back `:checked` here and rule 8 really
  fires on a receipt that carries no VAT ID. That is a genuine check.

  What it is **not** is validation of a VAT number. Article 215 gives the
  ISO 3166 alpha-2 prefix and nothing else; the body is Member State law
  taxlaw has not read. So a `true` from `registration-number-valid?` in the
  EU establishes the prefix SHAPE — not that the two letters name an actual
  Member State, not the body format, not a check digit. taxlaw says so in
  `:taxlaw/registration-format`, and this namespace lifts it onto the verdict
  as a `:limits` entry rather than letting a bare `:checked` be read as
  `the VAT number is valid`.

  ### `[:eu]` — and the credit is still HELD, by rule 11

  Before the bump an EU credit claim was held for the wrong reason
  (`:unchecked-jurisdiction` — taxlaw had never heard of the EU). Rule 7 was
  quietly doing two jobs: *no invoice rule was read* and *no preservation
  rule was read*. The bump split the first off and left the second uncovered,
  and the measured effect was that an EU claim quoting `DE123456789` went
  from HELD to COMMITTED with the preservation question never asked. Rule 11
  is that second job, named: 消費税法 第三十条第七項 conditions the credit on
  preserving the 請求書等, no analogue has been read for the EU, and an
  unread precondition is not a satisfied one.

  ### `[:eu]` — an electronic receipt kept on paper is NOT held here

  電帳法 第七条 obliges the HOLDER to preserve the electromagnetic record.
  Articles 218 and 246 oblige the MEMBER STATE to accept electronic form.
  Same facet key, opposite direction — so `requires-electronic-record?` is
  nil for the EU and rule 9 stays silent. Holding would enforce a rule the
  Directive does not contain, which is the mistake the ただし書 handling
  already refuses to make in the other direction. The verdict carries the
  limit instead, so `nobody could say` never renders as `preserved`.

  ### `[:us]` — held, with the reason, not with a bare refusal

  There is no federal VAT, so no federal analogue of a 適格請求書 exists and
  taxlaw marks the facet `:out-of-scope` with that reason. `credit-support`
  answers `:none` exactly as it did before the United States was catalogued,
  so rule 7 fires and the claim is held exactly as hard. What changed is the
  detail: it used to say the jurisdiction is `kotoba.taxlaw に無い`, which is
  now false. It carries taxlaw's `:taxlaw/why` instead.

  ### Retention — nil is an answer, and 7 is not the whole answer

  `retention-years` is nil for both `[:eu]` (Art 247(1) hands the period to
  the Member State) and `[:us]` (§ 1.6001-1(e) states a condition —
  `so long as the contents thereof may become material` — not a number).
  **That nil is the instrument's answer, not missing data**, and it is a
  different nil from `[:atlantis]`, where nobody read anything. Nor is
  Japan's 7 a bare 7: 法人税法施行規則 第五十九条 binds 青色申告法人, the clock
  starts at 起算日 (fiscal-year end + 2 months) rather than at the receipt,
  and 第二十六条の三 makes it 10 where 欠損金の繰越し is relied on. So this
  actor reports `:retention` three-valued and never hands anyone an
  unqualified integer.

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

(defn- registration-format-limit
  "What a `true` from the registration-format check did NOT establish.

  taxlaw returns `:taxlaw/registration-format` alongside a checked credit,
  naming what its pattern looked at and what it deliberately did not. In the
  EU that is the ISO 3166 alpha-2 prefix and nothing else — Article 215 gives
  the prefix, and the body of the number is Member State law nobody read. A
  surface that printed `:credit :checked` and stopped would be reporting
  `the VAT number is valid`, which is more than was measured.

  nil when the catalog names no unchecked component — Japan's `T` + 13 digits
  is the whole of the published format, so there is nothing to caveat."
  [credit]
  (let [{:keys [kind checked not-checked body-authority]}
        (:taxlaw/registration-format credit)]
    (when (seq not-checked)
      {:keihi/limit :registration-format-partial
       :keihi/facet :jurisdiction/input-tax-credit
       :keihi/kind kind
       :keihi/checked (vec (sort checked))
       :keihi/not-checked (vec (sort not-checked))
       :keihi/body-authority body-authority
       :keihi/detail (str "登録番号の形式検査で確認したのは "
                          (pr-str (vec (sort checked)))
                          " のみで、" (pr-str (vec (sort not-checked)))
                          " は検査していない。番号が有効であることの確認ではない")})))

(defn- electronic-preservation-limit
  "Why nothing could be said about preserving an 電磁的記録 here.

  `record-preservation` answers `:none` when the jurisdiction carries no
  `:rule/must-preserve-electronic-record?`. For `[:eu]` that is not an
  oversight: Articles 218/246 bind the Member State to ACCEPT electronic
  form, which is a different proposition from binding the holder to preserve
  it, and taxlaw says so in `:taxlaw/why`. Rule 9 correctly stays silent —
  and this is what stops that silence from reading as `preserved`."
  [preservation]
  (when (= :none (:taxlaw/coverage preservation))
    {:keihi/limit :electronic-preservation-unread
     :keihi/facet :jurisdiction/electronic-transaction
     :keihi/why (or (:taxlaw/why preservation)
                    "この法域について読まれた規定が無い")
     :keihi/detail (str "電磁的記録の保存義務について、この法域で読まれた規定は無い。"
                        "電子帳簿保存法 第七条 は日本の 保存義務者 を縛るもので、"
                        "他の法域へ広げていない（未検査は「保存済み」ではない）")}))

(defn- retention-answer
  "How long this receipt must be kept — three-valued, and never a bare
  integer.

    {:keihi/retention :unread}     no retention facet was read here. nil
                                   years because nobody looked.
    {:keihi/retention :no-period}  the facet WAS read and states no number.
                                   `:keihi/period-set-by` says who does set
                                   it — `:member-state` for the EU
                                   (Art 247(1)), `:materiality` for the US
                                   (§ 1.6001-1(e)). This nil is an answer.
    {:keihi/retention :years}      a number, carried WITH what qualifies it:
                                   who it binds, the longer period, and the
                                   fact that the clock starts at 起算日 and
                                   not at the receipt.

  The two nils are the distinction that matters. Collapsing them would let
  `the instrument states no period, go ask the Member State` and `nobody has
  read anything, do not destroy this` render as the same blank."
  [jurisdiction]
  (let [facet (taxlaw/facet-of jurisdiction :jurisdiction/retention)
        years (taxlaw/retention-years jurisdiction)]
    (cond
      (nil? facet)
      {:keihi/retention :unread
       :keihi/years nil
       :keihi/why (or (taxlaw/out-of-scope jurisdiction :jurisdiction/retention)
                      "この法域の保存期間について読まれた規定が無い")}

      (nil? years)
      {:keihi/retention :no-period
       :keihi/years nil
       :keihi/period-set-by (:rule/period-set-by facet)
       :keihi/provision (:rule/provision facet)
       :keihi/why (str "この条文は年数を定めていない。nil は「読めなかった」"
                       "ではなく条文の答えそのもの（"
                       (pr-str (:rule/period-set-by facet)) " が定める）")}

      :else
      {:keihi/retention :years
       :keihi/years years
       :keihi/years-with-loss-carryforward (:rule/years-with-loss-carryforward facet)
       :keihi/binds (:rule/binds facet)
       :keihi/provision (:rule/provision facet)
       :keihi/basis-date-provision (:rule/basis-date-provision facet)
       :keihi/why (str "起算日は領収の日ではない（"
                       (pr-str (:rule/basis-date-provision facet))
                       "）。年数は " (pr-str (:rule/binds facet))
                       " を縛るもので、欠損金の繰越しを用いる場合は "
                       (pr-str (:rule/years-with-loss-carryforward facet))
                       " 年")})))

(defn- tax-limits
  "Everything the statutes could not establish about this proposal, as facts
  rather than as absences.

  These are NOT violations. Each one is a question this actor was unable to
  ask in this jurisdiction, and every one of them would otherwise be
  indistinguishable from a satisfied check on a 200 response — which is the
  single failure mode this repo's whole tax layer is arranged against."
  [credit preservation]
  (into [] (remove nil?)
        [(registration-format-limit credit)
         (electronic-preservation-limit preservation)]))

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
    (let [credit (when (claims-input-tax-credit? proposal)
                   (taxlaw/credit-support jurisdiction receipt-record))
          preservation (taxlaw/record-preservation jurisdiction receipt-record)]
      {:credit credit
       :preservation preservation
       :invoice-preservation (shohizei/preservation-of-invoice
                              jurisdiction receipt-record proposal)
       ;; What could NOT be asked, and how long to keep the document. Both
       ;; ride on the verdict rather than being recomputed by each surface,
       ;; for the reason the whole report does: two computations of the same
       ;; question disagree eventually, and the console is where it shows.
       :limits (tax-limits credit preservation)
       :retention (retention-answer jurisdiction)})))

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

       ;; 7. the credit is claimed where no invoice rule was read.
       ;;
       ;; The detail reads taxlaw's own reason rather than asserting one.
       ;; Since taxlaw@d2663b54 a jurisdiction can be IN the catalog and
       ;; still answer `:none` for this facet — `[:us]` is, because there is
       ;; no federal VAT — so the old wording (`kotoba.taxlaw に無い`) became
       ;; false for exactly the jurisdiction it would be shown for. A hold
       ;; that misstates why it is holding sends the operator to the wrong
       ;; place; the disposition was right and the sentence was not.
       (= :none (:taxlaw/coverage credit))
       (conj {:rule :unchecked-jurisdiction
              :detail (str "仕入税額控除を主張しているが、法域 "
                           (pr-str (:jurisdiction employee-record))
                           " の適格請求書相当の規定は "
                           (if-let [why (:taxlaw/why credit)]
                             (str "kotoba.taxlaw が対象外としている: " why)
                             "kotoba.taxlaw に無い")
                           "（未検査は合格ではない）")})

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
                           (pr-str (:keihi/preservation invoice-preservation)))})

       ;; 11. rule 10's UNREAD arm, and it is not a refinement — it is a hole
       ;; the taxlaw bump opened. Rule 7 used to hold every non-JP credit
       ;; claim, so `no invoice rule was read here` and `no preservation rule
       ;; was read here` were never separable. `[:eu]` separates them: the
       ;; Directive gives an invoice rule, so rule 7 falls silent, and
       ;; 消費税法 第三十条第七項's precondition is still unread. Measured
       ;; 2026-08-18: without this clause an EU claim quoting `DE123456789`
       ;; went from HELD to COMMITTED and nothing in the verdict said the
       ;; preservation question had not been asked.
       ;;
       ;; This is NOT the ただし書 case — that requires the article to have
       ;; been reached (`:checked`), so an unread jurisdiction cannot
       ;; manufacture an escalation out of it, and the two clauses are
       ;; mutually exclusive by construction.
       (= :none (:keihi/coverage invoice-preservation))
       (conj {:rule :invoice-preservation-unread
              :detail (str "仕入税額控除の要件である請求書等の保存について、法域 "
                           (pr-str (:jurisdiction employee-record))
                           " で読まれた規定が無い（"
                           (:keihi/why invoice-preservation)
                           "）。未読の要件は満たされた要件ではない")})))))

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
