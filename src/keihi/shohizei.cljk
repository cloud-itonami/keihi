(ns keihi.shohizei
  "消費税法 第三十条第七項 — 帳簿及び請求書等の保存 as a precondition of
  仕入税額控除, three-valued.

  ## Why this is not in `kotoba-lang/taxlaw`

  taxlaw already answers two questions this actor asks: does the document
  carry a 登録番号 (`credit-support`), and was an 電子取引 preserved as an
  電磁的記録 (`record-preservation`, 電子帳簿保存法 第七条). It does not answer
  a third one that an expense actor cannot avoid: **is the 請求書等 preserved
  at all.** taxlaw's own catalog is honest about the gap — its
  `:jurisdiction/input-tax-credit` rule carries `:rule/review
  :reachable-not-read`, meaning 消費税法 is cited as an instrument and its
  article text was never read.

  So it was read here, and the rule lives here until a second actor needs it —
  which is exactly the trajectory `cloud-itonami-isco-4311`'s jurisdiction
  catalog took into taxlaw. Lifting it on the first caller would be inventing
  a shared abstraction from one data point.

  ## What was read, and from where

  `GET https://laws.e-gov.go.jp/api/2/law_data/363AC0000000108?law_full_text_format=json`
  retrieved 2026-08-17, 第三十条第七項, quoted verbatim in `provision`. Two
  clauses matter and they pull in opposite directions:

  * **本文** — where the business does not preserve the 帳簿及び請求書等 for a
    課税仕入れ, 第一項 (the credit itself) 「適用しない」. The disallowance is
    the default; preservation is what earns the credit. So an expense claim
    that has not ESTABLISHED preservation has not established the credit —
    silence is on the refusing side of this article, not the permissive side.

  * **ただし書 (proviso)** — unless 災害その他やむを得ない事情 made preservation
    impossible **and the business proves it** (「当該事業者において証明した
    場合」). A governor cannot verify a flood. What it can do is refuse to
    decide: an asserted exception is neither swallowed nor auto-honoured, it
    escalates to a human who can look at the proof. That is why
    `:keihi/exception-asserted?` is a separate key from `:keihi/preserved?`
    rather than a value of it.

  ## Three values, and what each one is not

    {:keihi/coverage :none}      no statute was READ for this jurisdiction.
                                 Not a pass and not a refusal. This is the
                                 only jurisdiction whose text was read, so
                                 every other one lands here — including
                                 jurisdictions taxlaw *does* catalogue,
                                 because taxlaw catalogues a different rule.

    {:keihi/coverage :no-claim}  no 仕入税額控除 was claimed. 第三十条 is about
                                 the credit; a claim that does not seek one
                                 raises no question under it. Distinct from
                                 :none — nothing was unavailable, there was
                                 nothing to ask.

    {:keihi/coverage :checked}   the article was applied. `:keihi/preserved?`
                                 is then present and boolean.

  `:keihi/preserved?` is absent in both non-`:checked` cases on purpose, so a
  caller reaching for it gets nil (falsey — the conservative answer) and a
  caller that wants to tell `refused` from `not asked` can.

  ## `:none` was inert until 2026-08-18, and that was the bug

  The paragraph above was written prospectively — no catalogued non-JP
  jurisdiction existed to test it against. taxlaw@d2663b54 added `[:eu]` and
  `[:us]`, and the prediction held here: both land on `:none`, because what
  the catalog read for them is a DIFFERENT rule.

  What did not hold was downstream. `keihi.governor` only acted on
  `:checked`, so `:none` from this namespace changed nothing — and for
  `[:eu]`, where taxlaw *does* supply an invoice rule, the governor's own
  `:unchecked-jurisdiction` fell silent too and an input-tax-credit claim
  COMMITTED with this article's precondition never asked. Rule 11
  (`:invoice-preservation-unread`) is the fix. **Returning the right
  three-valued answer is only half of it; something has to act on the arm
  that means `nobody looked`.**

  Dependency-free, like taxlaw and for the same reason: an actor that needs to
  know what 第三十条第七項 requires should not thereby acquire a store, a
  ledger or a graph."
  )

(def provision
  "消費税法 第三十条第七項, verbatim from the e-Gov law API. Both sentences are
  kept: dropping the proviso would turn a rebuttable disallowance into an
  absolute one, which is the direction an over-eager gate fails in."
  {:law/id "363AC0000000108"
   :law/title "消費税法"
   :provision "消費税法 第三十条第七項"
   :retrieved-via "e-Gov law API v2 GET /api/2/law_data/363AC0000000108"
   :retrieved-at "2026-08-17"
   :quote (str "第一項の規定は、事業者が当該課税期間の課税仕入れ等の税額の控除に係る"
               "帳簿及び請求書等（請求書等の交付を受けることが困難である場合、"
               "特定課税仕入れに係るものである場合その他の政令で定める場合における"
               "当該課税仕入れ等の税額については、帳簿）を保存しない場合には、"
               "当該保存がない課税仕入れ、特定課税仕入れ又は課税貨物に係る"
               "課税仕入れ等の税額については、適用しない。")
   :proviso (str "ただし、災害その他やむを得ない事情により、当該保存をすることが"
                 "できなかつたことを当該事業者において証明した場合は、この限りでない。")})

(def read-jurisdictions
  "Jurisdictions whose statutory text was actually read for THIS rule.

  Not `taxlaw/jurisdictions`, and not a boolean over it: taxlaw covering a
  jurisdiction means someone read *its* rules, not this one. Conflating the
  two would let a future `[:us :ca]` entry in taxlaw silently switch this
  article on for California."
  #{[:jp]})

(defn- path [j] (cond (vector? j) j (nil? j) nil :else [j]))

(defn preservation-of-invoice
  "Does the cited 請求書等 satisfy 第三十条第七項 for this claim?

  `claim` is the proposal; the article only bites when a 仕入税額控除 is being
  claimed (`:tax-treatment :input-tax-credit`), so a claim that seeks no
  credit gets `:no-claim` rather than a vacuous pass.

  Reasons on refusal:

    :preservation-not-recorded  nobody recorded whether the document is kept.
                                Under the 本文 the credit does not apply absent
                                preservation, and the ただし書 puts the burden of
                                proof on the business — so an unrecorded state
                                is a refusal here, unlike the `:not-declared`
                                limbo taxlaw uses for 電帳法 第七条 where the
                                obligation attaches to the transaction rather
                                than to the claim.

    :invoice-not-preserved      recorded as not kept (`:preservation :none`).

  When the claim asserts the proviso, `:keihi/exception-asserted?` is true and
  `:keihi/preserved?` stays false. The caller decides what to do with that —
  this namespace does not, because the article does not: it hands the question
  to whoever can weigh 証明."
  [jurisdiction receipt claim]
  (let [p (path jurisdiction)]
    (cond
      (not= :input-tax-credit (:tax-treatment claim))
      {:keihi/coverage :no-claim}

      (not (contains? read-jurisdictions p))
      {:keihi/coverage :none
       :keihi/unread [p]
       :keihi/why "消費税法 第三十条第七項 was read for [:jp] only"}

      :else
      (let [pres (:preservation receipt)
            kept? (contains? #{:electronic :paper} pres)
            exception (:preservation-exception claim)]
        {:keihi/coverage :checked
         :keihi/jurisdiction p
         :keihi/preserved? kept?
         :keihi/preservation pres
         :keihi/provision (:provision provision)
         :keihi/exception-asserted? (boolean (and (not kept?) exception))
         :keihi/reason (cond kept? nil
                             (nil? pres) :preservation-not-recorded
                             :else :invoice-not-preserved)}))))
