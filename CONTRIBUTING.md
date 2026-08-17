# Contributing

AGPL-3.0-or-later. Two conditions on anything that changes a rule:

1. **Only enforce a statute you have read.** Fetch the article text —

   ```bash
   curl -sS "https://laws.e-gov.go.jp/api/2/law_data/<LAW_ID>?law_full_text_format=json"
   ```

   — quote it, and record how it was retrieved. A rule enforced on the
   strength of a URL nobody opened is not a cited rule, and this repo marks
   the difference (`:rule/review`, `keihi.shohizei/provision`).

2. **Show the test failing.** Break the implementation, run `clojure -M:test`,
   and put the reddened test names in the PR. A test that stays green when you
   break the thing it tests is worthless; the README's mutation table is what
   that evidence looks like here. Check that what you broke and what went red
   are the same thing — a mutation that reddens the whole suite has usually
   broken the function rather than the invariant.

`deps.edn` must stay free of `:local/root`, including transitively. Prove it by
running the suite from a directory with no sibling checkouts, not by grepping
the file.
