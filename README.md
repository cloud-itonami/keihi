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

**87 tests / 353 assertions green**, measured 2026-08-17 from a fresh
`git clone` into `/tmp` with no sibling checkouts (see *Forkable for real*,
below). Two store backends answer identically under one contract test, and
three HTTP routes are the whole network surface.

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

## Two store backends, and a contract test that would notice a disagreement

`keihi.store/Store` has two implementations:

| | `MemStore` | `DatomicStore` |
|---|---|---|
| substrate | one atom | `langchain.db` (Datomic-API EAV, DataScript in-process) |
| survives the process | no | whatever `langchain.db`'s `:db-api` is bound to |
| streams | `conj` onto a vector | seq-keyed EDN blobs via `kotoba-lang/langchain-store` |

The EDN-blob codec, the identity schema and the seq-keyed stream helpers come
from [`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store),
which exists because 190 store files in this fleet had hand-rolled the same
two-liner. It is not re-hand-rolled here.

**`datomic-store` is honest about what it is.** With the default in-process
DataScript it survives no longer than `MemStore` does; what it buys
unconditionally is that pointing keihi at a real Datomic or a kotoba-server pod
is a `:db-api` swap and not a rewrite. Claiming durability the function cannot
observe would be exactly the fabrication the governor spends ten rules refusing.

`keihi.store-contract-test` runs **every** assertion against **both**, because a
second backend that quietly disagrees with the first is worse than no second
backend: the disagreement only appears on the deployment that has the durable
one. Two properties are load-bearing:

* **the ledger is ordered and append-only.** It is the only record of what this
  actor refused and why, and the actor never reads it back — so a backend that
  returned it out of order would produce an audit trail in which the amendment
  precedes the claim and nothing would complain.
* **`:origin`, `:preservation` and `:registration-number` survive the round
  trip.** Those three fields are what 電子帳簿保存法 第七条, 消費税法 第三十条第七項
  and the 登録番号 rule actually turn on. A backend that dropped one would turn a
  HARD hold into a clean commit, silently, on the durable deployment only.

Both are demonstrated rather than asserted — mutations 21, 22 and 23 below
break exactly one backend each, and the contract test catches all three while
every single-backend suite stays green.

## The HTTP surface: three routes

```text
POST /api/claim        submit an expense claim
GET  /api/claim/:id    the whole life of one claim, and its verdict
GET  /api/ledger       the caller's own slice of the append-only ledger
```

Portable `.cljc` request→response functions in `keihi.edge.endpoints`: a
response is `{:status n :body {...}}`, there are no host effects and no
framework, and the caller's DID arrives already verified (CACAO is
`kotoba-lang/org-chainagnostic-cacao`'s job). There is no Cloudflare binding in
this repo yet, and shipping an untested one would be worse than saying so.

Of the four ops only `:submit-claim` is exposed. `:disburse` always escalates,
`:amend-claim` rewrites a submitted figure and `:reject-claim` is a decision
about someone's money — none of them belongs on a network path.
`submit-claim-core!` hard-codes the op and takes the employee from the DID, so a
body naming `:disburse` or another employee gets a claim submitted under the
caller's own name.

**Three outcomes, three statuses, no `:ok` boolean.** A claim can commit,
escalate or hold; a boolean has two values. So `:disposition` is on the body and
the status matches it — 200 / **202** / 409. 202 for an escalation because
`awaiting a human signature` is neither `paid` nor `refused`, and calling it
either is a lie in one direction or the other. `:ok` survives only on responses
about the *request* (503 / 403 / 400 / 404 / 405), where nothing three-valued is
being reported.

**Every claim response carries `:tax`.** The same three-valued coverage report
the verdict holds, reduced to one keyword per statute — `:checked`,
`:none`, `:not-declared`, `:not-claimed`, `:not-assessed`. A 200 that omitted it
would render *we read 消費税法 and this claim satisfies it* and *nobody has read
the law where this employee works* as the same green tick.

**An unknown claim is 404, never an empty 200** — `no entries` and `no such
claim` are the same bytes if the answer is a list, and only one of them means
the actor knows anything. A claim belonging to another employee returns the
byte-identical 404, deliberately: a 403 there would confirm the claim exists and
make a claim id something a stranger can probe for.

An absent allow-list serves **503** on all three routes, and an unset
`KEIHI_STORE` serves 503 too — refusing beats returning `:no-employee` and
blaming the caller for a storeless deployment.

The **whole** ledger has no HTTP representation. It is every employee's
spending, and an actor that hands that to whoever holds one valid DID has
published the company's books. `:scope :caller-only` is on the body so nobody
mistakes the slice for the whole.

### One change to the actor came out of this

The graph now runs `:decide → :escalate → :request-approval`, and `:escalate`
appends to the ledger before the interrupt. Previously an escalated claim
produced **zero** ledger entries until a human resumed the thread, so *awaiting
approval* and *never submitted* were the same observation and no read surface
could tell them apart. Every ledger fact also carries `:claim-id` and
`:employee-id` at the top level on all three dispositions — nesting the identity
inside `:record` made a committed claim findable and a held one not, the held
claim being the one somebody needs to look up.

## Forkable for real: zero `:local/root`

`deps.edn` declares four git deps and no sibling paths. That is a hard
property, not a preference: a `:local/root` makes this repo unforkable (the
line below would be false) and structurally ungateable on the murakumo fleet,
which ships **one repo's tree and no siblings** — a gate would fail on a
missing directory and read as a broken build rather than a broken dependency.

Grepping does not prove it, twice over. A transitive `:local/root` in a
dependency is just as fatal and does not appear here; and
`langchain-store`'s `deps.edn` contains the literal string `:local/root` inside
a *comment* explaining that it used to have one, so a grep of that file reports
a hit that is not there. It was checked by **parsing** each `deps.edn` as EDN
and walking `:deps` plus every alias's `:extra-deps` / `:replace-deps` /
`:override-deps`:

```
langchain-store @ b986669f   5 coordinates, 0 :local/root
langchain-clj   @ 51e7b61e   6 coordinates, 0 :local/root
keihi (this repo)            6 coordinates, 0 :local/root
```

And then the only check that actually settles it — the suite run from a fresh
`git clone` into `/tmp`, where no checkout of `governor`, `taxlaw`, `langgraph`
or `langchain-store` exists:

```
$ git clone https://github.com/cloud-itonami/keihi.git /tmp/keihi && cd /tmp/keihi
$ clojure -M:test
Ran 87 tests containing 353 assertions.
0 failures, 0 errors.
```

## Proving the tests can fail

A suite that stays green when you break the thing it tests is worthless, and
the only way to tell the two apart is to break it. **All 33 mutations below
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
| 21 | **`DatomicStore` only**: `next-seq` always returns 0 | 6 — `the-ledger-is-append-only-and-ordered`, `identical-ledger-facts-both-land…`, `records-append-in-order…`, `claim-history-is-the-whole-life…`, `ledger-of-scopes-to-one-employee`, `a-disbursement-escalates-then-commits…` |
| 22 | **`MemStore` only**: the ledger prepends instead of appending | 4 — `the-ledger-is-append-only-and-ordered`, `claim-history-is-the-whole-life…`, `a-disbursement-escalates-then-commits…`, `the-ledger-route-serves-the-callers-own-slice-only` |
| 23 | **`DatomicStore` only**: `register-receipt!` drops `:preservation` | 2 — `the-three-statute-bearing-receipt-fields-survive-the-round-trip`, `a-receipt-that-declares-nothing-round-trips-as-declaring-nothing` |
| 24 | `:escalate` stops appending to the ledger | 3 — `a-disbursement-escalates-then-commits…`, `an-asserted-proviso-is-202-and-pays-nothing`, `a-claims-history-reads-back-in-order-with-its-verdict` |
| 25 | ledger facts lose `:claim-id` | 9 — every contract-suite actor test and every edge read test |
| 26 | let the body choose the `:op` | 1 — `the-body-cannot-choose-the-op-and-so-cannot-reach-the-money` |
| 27 | let the body choose the employee | 1 — `the-employee-comes-from-the-did-not-the-body` |
| 28 | an unknown claim returns 200 with an empty history | 2 — `an-unknown-claim-is-404-and-never-an-empty-200`, `another-employees-claim-is-indistinguishable…` |
| 29 | `tax-coverage` reports `:checked` for everything | 2 — `an-unchecked-jurisdiction-reports-none-and-not-silence`, `not-claimed-not-assessed-and-checked-are-three-different-answers` |
| 30 | drop the caller scoping on `GET /api/claim/:id` | 1 — `another-employees-claim-is-indistinguishable…` |
| 31 | an absent allow-list serves the endpoint anyway | 1 — `an-absent-allowlist-serves-503-on-every-route` (3 assertions) |
| 32 | an escalation answers 200 instead of 202 | 1 — `an-asserted-proviso-is-202-and-pays-nothing` |
| 33 | the router builds its own store per request | 2 — `the-router-carries-reads-across-requests-on-the-durable-backend`, `the-router-serves-three-routes-and-refuses-the-rest` |

**Mutations 21, 22 and 23 are the ones the contract test exists for.** Each
breaks exactly ONE backend.

Under **21** and **23** — both `DatomicStore`-only — every other suite stays
green: `actor-test` (7), `governor-test` (14), `tax-rules-test` (20),
`conformance-test` (5) and all 26 edge tests, 72 tests in total, because none of
them ever constructs the durable backend. Only the file that runs both
implementations notices, and it reddens 6 tests and 2 tests respectively. That
is the entire argument for a second backend having a contract test rather than
a smoke test.

Under **22** — `MemStore`-only — the contract suite catches it (3 tests) and one
edge test catches it too, because the edge happens to exercise MemStore. The
asymmetry is the point: the backend the rest of the suite uses gets incidental
coverage, and the one it does not use gets none at all unless something is
written for it. Before this file existed, mutations 21 and 23 would have
shipped green.

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
clojure -M:test     # 87 tests, 353 assertions
clojure -M:lint     # clj-kondo, 0 errors 0 warnings
```

| suite | tests | what it holds |
|---|---|---|
| `keihi.governor-test` | 14 | provenance, actuation, receipt basis, ownership, arithmetic |
| `keihi.tax-rules-test` | 20 | the four statute-driven rules and their three-valued wiring |
| `keihi.actor-test` | 7 | the graph obeys the verdict |
| `keihi.conformance-test` | 5 | every verdict is internally consistent |
| `keihi.store-contract-test` | 15 | `MemStore` ≡ `DatomicStore` |
| `keihi.edge.endpoints-test` | 26 | the three routes, and what they refuse |

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

**Not tax advice.** Two statutes are enforced here and their text was read
before they were enforced. Everything the catalogues do not cover is reported
as uncovered rather than assumed benign — which is a property of the mechanism,
not a claim of completeness.
## 仕訳 — an approved claim becoming a journal entry

Deciding is not bookkeeping. **A claim that was approved and never became an
entry is a payment nobody's books show.** `keihi.shiwake/entry-request` turns
a committed claim into the `:draft-entry` request
[`cloud-itonami-isco-4311`](https://github.com/cloud-itonami/cloud-itonami-isco-4311)
accepts at `POST /api/entry`.

**It produces a value; it does not make a call.** No HTTP, no client, no
reference to 4311 — asserted by a test that scans this namespace's own
source. Two reasons, and the second carries more weight:

1. This actor's ceiling is that it proposes. Writing into another actor's
   ledger would be the actuation the whole design refuses.
2. **A call would make the accounts this actor's business, and they are
   not.** Which account a train fare debits is the client's chart, and
   `kotoba-lang/shohyo` refuses to guess what an account is precisely because
   a statement that guessed still balances. So the mapping is an argument
   here too.

Every way the hand-off could quietly lose a claim is a named status rather
than nil:

| | |
|---|---|
| `:not-approved` | held or escalated — **its own status**, because a caller treating "no entry" as "nothing to do" would skip exactly the claims somebody must look at |
| `:no-mapping` | the category has no accounts. No suspense-account fallback: that would post the entry and make the missing decision invisible. A half-filled mapping is no mapping — an entry missing one line balances by having lost it |
| `:unusable-claim` | no positive amount, or no receipt cited |

The receipt travels as `:source-doc`, so 4311 refuses an entry citing a
document its own registry does not know. That is the right way round: the
ledger's registry is the one that counts, not this actor's.

`entry-requests` returns `{:ok [...] :skipped [...]}` rather than filtering,
and each refusal carries the claim it refused. A batch that dropped what it
could not convert would report a clean run.

Measured, all seven mutations red: emit an entry for an unapproved claim (9),
fall back for an unmapped category (8), drop the credit line (2), accept a
negative amount (2), have the batch discard skips (2), drop the claim from a
refusal (1), drop the source document (2).

