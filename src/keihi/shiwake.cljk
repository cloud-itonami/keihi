(ns keihi.shiwake
  "仕訳 — an approved expense claim as a journal entry request.

  This actor decides whether a reimbursement may be paid. Deciding is not
  bookkeeping: a claim that was approved and never became an entry is a
  payment nobody's books show. `cloud-itonami-isco-4311` owns the ledger;
  this namespace produces the value that actor accepts.

  ## It produces a value, it does not make a call

  No HTTP, no client, no reference to 4311 at all. `entry-request` returns
  the map `POST /api/entry` takes, and something outside both actors carries
  it. Two reasons, and the second is the load-bearing one:

  1. This actor's ceiling is that it proposes. Reaching across to write into
     another actor's ledger would be an actuation this repo has spent its
     whole design refusing.
  2. **A call would make the accounts this actor's business.** They are not.
     Which account a meal or a train fare debits is the client's chart, and
     `kotoba-lang/shohyo` refuses to guess what an account is precisely
     because a statement that guessed still balances. So the mapping is an
     argument here too.

  ## The mapping is supplied, and partial is refused

  `entry-request` takes `{category {:debit account :credit account}}`. A
  category with no mapping does not fall back to a suspense account and does
  not get dropped — it returns `:no-mapping`. A journal entry missing one
  line balances by having lost it, which is the failure this plane already
  caught once at the projection layer."
  (:require [kotoba.lang.text :as str]))

(defn- positive-amount? [x] (and (number? x) (pos? x)))

(defn entry-request
  "An approved claim as the `:draft-entry` request `isco-4311` accepts, or
  the reason there is none.

      {:shiwake/status :not-approved}   the claim was held or escalated
      {:shiwake/status :no-mapping}     the category has no accounts
      {:shiwake/status :unusable-claim} amount or receipt missing
      {:shiwake/status :ok
       :shiwake/request {:op :draft-entry :source-doc … :lines [...]}}

  `:not-approved` is deliberately its own status rather than nil. A caller
  that treats \"no entry\" as \"nothing to do\" would silently skip a claim
  that was actually refused, and refused claims are the ones somebody has to
  look at."
  [{:keys [disposition claim] :as _committed} mapping]
  (let [{:keys [category amount receipt currency]} claim
        {:keys [debit credit]} (get mapping category)]
    (cond
      (not= :commit disposition)
      {:shiwake/status :not-approved :shiwake/disposition disposition}

      (or (not (positive-amount? amount)) (str/blank? (str receipt)))
      {:shiwake/status :unusable-claim
       :shiwake/why (str "amount must be a positive number and a receipt must "
                         "be cited; got " (pr-str {:amount amount :receipt receipt}))}

      (or (str/blank? (str debit)) (str/blank? (str credit)))
      {:shiwake/status :no-mapping
       :shiwake/category category
       :shiwake/why (str "no debit/credit accounts mapped for "
                         (pr-str category) "; this actor does not choose them")}

      :else
      {:shiwake/status :ok
       :shiwake/request
       {:op :draft-entry
        :source-doc receipt
        ;; The receipt is the source document on BOTH sides of the hand-off:
        ;; 4311 will hold an entry whose cited document it has no record of,
        ;; so a claim approved here against a receipt that was never
        ;; registered there is refused rather than posted. That is the right
        ;; way round -- the ledger's registry is the one that matters.
        :lines [{:side :dr :account debit  :amount amount :currency currency}
                {:side :cr :account credit :amount amount :currency currency}]}})))

(defn entry-requests
  "`entry-request` over many committed claims, keeping the refusals.

  Returns `{:ok [...] :skipped [...]}` rather than filtering. A batch that
  quietly dropped what it could not convert would report a clean run and
  leave the unconvertible claims invisible, which is the shape this
  workspace keeps finding."
  [committed mapping]
  (let [rs (map #(assoc (entry-request % mapping) :shiwake/claim %) committed)]
    {:ok (vec (filter #(= :ok (:shiwake/status %)) rs))
     :skipped (vec (remove #(= :ok (:shiwake/status %)) rs))}))
