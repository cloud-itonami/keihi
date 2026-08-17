# keihi 経費

**Expense reimbursement with a governor that will not pay a figure no
document supports.** An itonami actor (advisor-LLM ⊣ independent governor,
append-only audit ledger, ADR-2607011000): the advisor may only ever
*propose*, and the governor is a separate system able to refuse it.

```text
receipt  ──▶ :submit-claim ──┐
             :amend-claim    ├──▶ KeihiAdvisor ──▶ KeihiGovernor ──▶ commit | approve | HOLD
             :reject-claim   │        (proposes only)   (re-runs the statutes)
             :disburse     ──┘                                   │
                                                                 └──▶ append-only ledger
```

Two capability libraries do the work this repo does not:
[`kotoba-lang/governor`](https://github.com/kotoba-lang/governor) assembles
the verdict, and [`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw)
answers what a tax record must carry. Neither is vendored.

**46 tests / 175 assertions green**, measured 2026-08-17 from a directory
with no sibling checkouts (see *Forkable for real*, below).

## Ten HARD invariants (never approvable past)

| # | rule | what it refuses | whose rule |
|---|---|---|---|
| 1 | `:no-employee` | a claim on behalf of someone unregistered | `gov/missing-subject` |
| 2 | `:no-actuation` | a proposal whose `:effect` is not `:propose` | `gov/no-actuation` |
| 3 | `:no-receipt` | a claim citing no receipt — an invented expense | keihi |
| 4 | `:unknown-receipt` | a claim citing a receipt that does not exist | `gov/unknown-scope` |
| 5 | `:receipt-wrong-employee` | being paid for someone else's dinner | `gov/scope-owner-mismatch` |
| 6 | `:amount-mismatch` | `:amount` ≠ the sum of `:lines` | keihi |
| 7 | `:unchecked-jurisdiction` | 仕入税額控除 claimed where nobody read the law | taxlaw |
| 8 | `:invalid-registration-number` | 仕入税額控除 on a receipt with no valid 登録番号 | taxlaw |
| 9 | `:electronic-record-not-preserved` | an 電子取引 kept only on paper — 電子帳簿保存法 第七条 | taxlaw |
| 10 | `:invoice-not-preserved` | 仕入税額控除 whose 請求書等 is not preserved — 消費税法 第三十条第七項 | **keihi** |

Rule 6 is the one that reads oddly until you try to approve past it. Whichever
number is wrong, nobody knows which — so paying either figure pays an amount no
document supports, and there is nothing for a human to sign off.

Rules 7, 8 and 10 fire **only** on a proposal that claims 仕入税額控除. Rule 9
does not: 第七条 binds the 保存義務者 whenever an 電子取引 happened, not only when
a credit is claimed for it. The actor does not invent a tax position in order
to have one to check.

### Escalations (human sign-off; the operation is legitimate)

* **`:disburse`** — real money leaves the company, and this is the only
  operation here whose effect is irreversible outside this system's storage.
* **保存の例外の主張** — see below.
* **confidence < 0.6**, including an *absent* `:confidence` key. A proposal
  that has not said how confident it is has not said it is confident.

## The one statute this repo reads on its own authority

Rules 7–9 delegate to taxlaw. Rule 10 could not, because taxlaw does not
answer it and says so: its `:jurisdiction/input-tax-credit` entry carries
`:rule/review :reachable-not-read` — 消費税法 is cited as an *instrument* and
its article text was never read.

So it was read here, from the e-Gov law API rather than from a page anyone
merely linked:

```
GET https://laws.e-gov.go.jp/api/2/law_data/363AC0000000108?law_full_text_format=json
```

**消費税法 第三十条第七項** (retrieved 2026-08-17), quoted verbatim in
`keihi.shohizei/provision`. Two clauses matter and they pull opposite ways:

* **本文** — where the business does not preserve the 帳簿及び請求書等,
  第一項 (the credit) 「適用しない」. Disallowance is the *default*; preservation
  is what earns the credit. So a claim that has not **established** preservation
  has not established the credit, and an unrecorded preservation state is on
  the refusing side of this article. That is why rule 10 refuses
  `:preservation-not-recorded`, where taxlaw's 第七条 rule correctly leaves an
  undeclared `:origin` in `:not-declared` limbo — there, the obligation attaches
  to the transaction; here, it attaches to the claim.

* **ただし書** — unless 災害その他やむを得ない事情 made preservation impossible
  **and the business proves it** (「当該事業者において証明した場合」). A governor
  cannot verify a flood. Refusing outright would enforce a rule the statute
  does not contain; passing would enforce nothing. So an asserted exception
  **escalates**: `:keihi/exception-asserted?` is a key of its own, `:keihi/preserved?`
  stays `false`, and a human is handed the question of whether the proof holds.
  The exception lifts *only* its own article — a claim asserting it still fails
  rules 8 and 9 (measured: mutation 13 below).

`keihi.shohizei/read-jurisdictions` is `#{[:jp]}` and is deliberately **not**
derived from `taxlaw/covered?`. taxlaw covering a jurisdiction means someone
read *its* rules, not this one; deriving the set would let a future `[:us :ca]`
entry silently switch 消費税法 on for California.

While verifying, taxlaw's own quote of **電子帳簿保存法 第七条** was refetched
from `law_data/410AC0000000025` and matched its catalog byte for byte.

## Unknown is never a pass, and the verdict says which unknown

Every three-valued answer is split three ways, never two, and what could not
be checked rides on the verdict under `:tax` rather than being swallowed:

```clojure
{:tax {:credit               {:taxlaw/coverage :none | :checked ...}
       :preservation         {:taxlaw/coverage :none | :not-declared | :checked ...}
       :invoice-preservation {:keihi/coverage  :none | :no-claim | :checked ...}}}
```

The pass-shaped key (`:taxlaw/supported?`, `:taxlaw/preserved?`,
`:keihi/preserved?`) is **absent** in every non-`:checked` case rather than
false, so a caller reaching for it gets the conservative answer and a caller
that wants to tell *refused* from *not asked* can. A console showing "we looked
and it was fine" and "nobody looked" as the same green tick has lost the only
distinction these libraries exist to preserve.

`:tax` is `nil` — not an empty assessment — when there was nothing to assess,
so the actor never manufactures a finding to have one.

## Forkable for real: zero `:local/root`

`deps.edn` declares three git deps and no sibling paths. That is a hard
property, not a preference: a `:local/root` makes this repo unforkable (the
line below would be false) and structurally ungateable on the murakumo fleet,
which ships **one repo's tree and no siblings** — a gate would fail on a
missing directory and read as a broken build rather than a broken dependency.

Grepping `deps.edn` does not prove it, because a transitive `:local/root` is
just as fatal and does not appear there. So the suite was run from `/tmp/keihi`,
where no checkout of `governor`, `taxlaw` or `langgraph` exists:

```
$ cd /tmp/keihi && clojure -M:test
Ran 46 tests containing 175 assertions.
0 failures, 0 errors.
```

## Proving the tests can fail

A suite that stays green when you break the thing it tests is worthless, and
the only way to tell the two apart is to break it. **All 20 mutations below
were applied one at a time to a clean tree and the suite re-run; every one of
them reddened at least one test, and none survived.**

| # | mutation | tests reddened |
|---|---|---|
| 1 | drop the employee registration check | 3 — `hard-on-unregistered-employee`, `several-failures-are-all-reported`, `every-named-hard-case-…` |
| 2 | accept any `:effect` | 5 — incl. `hard-on-actuation`, `the-advisor-cannot-write-past-the-governor` |
| 3 | allow a claim citing no receipt | 6 — incl. `holds-an-invented-expense-without-writing-anything`, `a-hold-is-recorded-in-the-ledger` |
| 4 | never apply the unknown-receipt check | 3 — incl. `hard-on-a-receipt-that-does-not-exist` |
| 5 | drop the ownership comparison | 3 — incl. `hard-on-another-employees-receipt` |
| 6 | stop comparing `:amount` to `:lines` | 6 — incl. `hard-on-a-claim-with-no-amount-at-all`, `holds-a-claim-that-does-not-add-up` |
| 7 | treat an uncatalogued jurisdiction as a pass | 5 — incl. both uncatalogued cases and `the-jurisdiction-is-the-employees-not-the-proposals` |
| 8 | ignore taxlaw's 登録番号 refusal | 5 — incl. missing *and* malformed, and `the-proviso-lifts-only-its-own-article` |
| 9 | ignore 電帳法 第七条 | 5 — incl. `the-article-7-rule-is-not-scoped-to-a-tax-claim` |
| 10 | ignore 消費税法 第三十条第七項 | 4 — both `:none` and `:preservation-not-recorded` cases |
| 11 | let `:disburse` commit unattended | 3 — incl. `interrupts-then-commits-a-disbursement-on-human-approval` |
| 12 | swallow the ただし書 assertion | 2 — `the-proviso-escalates-rather-than-passing-or-refusing` |
| 13 | let the ただし書 be refused outright | 3 — incl. `the-proviso-lifts-only-its-own-article` |
| 14 | count an unrecorded preservation as kept | 1 — `hard-on-a-credit-claim-whose-preservation-was-never-recorded` |
| 15 | stop putting `:tax` on the verdict | 5 — incl. `a-receipt-declaring-no-origin-is-not-a-SILENT-pass` |
| 16 | let the proposal choose its own jurisdiction | 1 — `the-jurisdiction-is-the-employees-not-the-proposals` |
| 17 | default a missing `:confidence` to 1.0 | 2 — `an-absent-confidence-key-is-zero-not-trust` |
| 18 | reproduce the fleet's measured drift | 3 — `every-verdict-is-well-formed`, `the-drift-that-happened-elsewhere-cannot-happen-here`, `escalation-carries-a-reason` |
| 19 | enforce 消費税法 where it was never read | 1 — `the-article-30-rule-only-fires-where-the-statute-was-read` |
| 20 | assess the ops that act on a settled claim | 1 — `a-rejection-is-not-blocked-by-the-claim-it-rejects` |

Every mutation also reddens `every-named-hard-case-actually-raises-the-rule-it-is-named-for`
or `the-case-set-actually-covers-the-three-dispositions` where the disposition
count moves; those are the evidence floors and are counted above.

**Mutation 18 was measured twice, and the first measurement was wrong.** The
first attempt reddened 41 of 46 tests, which looks like an emphatic success and
was not: the edit left a stray `(comment …)` as `check`'s return value, so the
function returned `nil`. That is a broken function, not a drifted verdict —
what was broken and what was reported did not match. Redone surgically (wrap
`gov/verdict` and set `:escalate? (or hard? escalate?)`, exactly isco-5419's
drift) it reddens **3** tests, all in the conformance suite that exists for it,
and nothing else. The count in the table is the second measurement.

## The conformance suite

`kotoba-lang/governor` measured 376 hand-copied governors and found one that
had silently drifted into reporting a HARD violation as escalatable — so an
approval queue would show a permanently-refused operation as awaiting sign-off
and invite a human to try to approve something no approval can pass. The drift
was invisible through the actor's own graph, because the router tests `:hard?`
first, and so no test caught it.

`keihi.conformance-test` is the test that would have. It drives `check` through
15 named cases — one clean, ten HARD, four escalating — and asserts only that
whatever was decided is internally consistent — plus two evidence floors, because a conformance
suite whose cases all land in one disposition passes while checking almost
nothing. One of those floors checks that a case *named* `:hard/amount-mismatch`
actually raises `:amount-mismatch`, so a miscounted case cannot inflate the
other floor.

## Running it

```bash
clojure -M:test     # 46 tests, 175 assertions
clojure -M:lint     # clj-kondo
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

**Not tax advice.** Two statutes are enforced here and their text was read
before they were enforced. Everything the catalogues do not cover is reported
as uncovered rather than assumed benign — which is a property of the mechanism,
not a claim of completeness.
