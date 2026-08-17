(ns keihi.store
  "SSoT for 経費 (keihi) — the expense-reimbursement actor. Store is a
  protocol injected into the `keihi.actor` StateGraph; `MemStore` is the
  default, deterministic, zero-dep backend, and a Datomic / kotobase-backed
  implementation can be swapped in without touching the actor or the governor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled
  on `cloud-itonami-isco-4311`'s bookkeeping.store, with the source-document
  registry specialised to 領収書/請求書.

  Domain:

    employee — a registered claimant (:employee-id, :name, :jurisdiction).
               The jurisdiction lives HERE and nowhere else that a proposal
               can reach, because an advisor able to pick its own
               jurisdiction could pick one whose rules it satisfies.

    receipt  — a registered source document (:receipt-id, :employee-id,
               :kind, :amount, and three fields the statutes below actually
               turn on:

                 :origin              :electronic-transaction | :paper
                 :preservation        :electronic | :paper | :none
                 :registration-number 適格請求書発行事業者の登録番号

               Every claim MUST cite one. A reimbursement claim without a
               receipt is an invented expense, and the governor HARD-holds
               it (the fleet's no-fabricated-basis discipline, 経費 edition).

               Note the deliberate asymmetry between an ABSENT `:preservation`
               and `:none`: absent means nobody recorded it, `:none` means
               someone recorded that the document is gone. Both refuse a
               仕入税額控除 claim, for reasons the governor keeps distinct.

    record   — a committed operating record (submitted claim, amendment,
               disbursement, rejection) — written ONLY via commit-record!,
               never mutated.

    ledger   — an append-only audit trail of every proposal / verdict /
               disposition, regardless of outcome (commit or hold)."
  )

(defprotocol Store
  (employee [s employee-id])
  (receipt [s receipt-id])
  (records-of [s employee-id])
  (ledger [s])
  (register-employee! [s employee])
  (register-receipt! [s receipt])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (employee [_ employee-id] (get-in @a [:employees employee-id]))
  (receipt [_ receipt-id] (get-in @a [:receipts receipt-id]))
  (records-of [_ employee-id] (filter #(= employee-id (:employee-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-employee! [s e]
    (swap! a assoc-in [:employees (:employee-id e)] e) s)
  (register-receipt! [s r]
    (swap! a assoc-in [:receipts (:receipt-id r)] r) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:employees {} :receipts {}
                                    :records [] :ledger []}
                                   seed)))))
