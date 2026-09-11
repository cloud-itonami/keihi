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
               disposition, regardless of outcome (commit, escalation or
               hold).

  Two backends implement this protocol and `keihi.store-contract-test` runs
  the same assertions against both. A backend that silently disagreed with
  the other about ledger ORDER would be the worst kind of bug here: the
  ledger is the only record of what was refused, and an audit trail whose
  order depends on which backend you deployed is not an audit trail."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

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

;; ---------------------------------------------------------------------------
;; DatomicStore (langchain.db)
;;
;; The same protocol over a Datomic-API-compatible EAV store, so the backend is
;; a swap and not a rewrite (cloud-itonami-isic-6511's underwriting.store is
;; the fleet's reference adopter; kintai and tehai are the two siblings that
;; already did this). Pure `.cljc`: it runs offline against langchain.db's
;; in-process DataScript, and the SAME record points at a real Datomic or a
;; kotoba-server pod by swapping langchain.db's `:db-api` (langchain.kotoba-db).
;;
;; The LEDGER is why this exists. `MemStore` keeps the audit trail for exactly
;; as long as the process lives, and an expense actor whose record of what it
;; refused disappears on restart cannot answer the one question anybody asks it
;; later — why was this claim not paid. The record stream has the same problem
;; from the other side: a reimbursement that was committed and then forgotten
;; is a reimbursement that can be claimed twice.
;;
;; Both streams are seq-keyed and append-only on both backends. `:record/seq`
;; and `:ledger/seq` are `:db.unique/identity`, so re-appending at a seq that
;; already exists UPSERTS rather than forking the log — which is precisely why
;; `next-seq` has to be right, and why the contract test asserts order rather
;; than merely count.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (ls/identity-schema [:employee/id :receipt/id :record/seq :ledger/seq]))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (employee [_ employee-id]
    (ls/blob-lookup conn :employee/id :employee/edn employee-id))
  (receipt [_ receipt-id]
    (ls/blob-lookup conn :receipt/id :receipt/edn receipt-id))
  (records-of [_ employee-id]
    (filterv #(= employee-id (:employee-id %))
             (ls/read-stream conn :record/seq :record/edn)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-employee! [s e]
    (ls/put-blob! conn :employee/id :employee/edn (:employee-id e) e) s)
  (register-receipt! [s r]
    (ls/put-blob! conn :receipt/id :receipt/edn (:receipt-id r) r) s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn
                     (next-seq conn :record/seq) record) s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact
                     (next-seq conn :ledger/seq) fact) s))

(defn datomic-store
  "A DatomicStore over a fresh in-process langchain.db connection.

  In-process is the DEFAULT, not the guarantee. This function hands back a
  store whose durability is whatever langchain.db's `:db-api` is bound to;
  with the default in-process DataScript it survives no longer than MemStore
  does. What it buys unconditionally is that the swap is a swap — the actor,
  the governor and the edge are unchanged, and the contract test proves the
  two backends answer identically."
  []
  (->DatomicStore (d/create-conn schema)))

;; ---------------------------------------------------------------------------
;; Derived reads over the ledger
;;
;; Plain functions over the protocol rather than protocol methods, deliberately:
;; a backend cannot disagree with another about something neither of them
;; implements. They are still exercised against BOTH backends in the contract
;; test, because what they are really asserting is that `ledger` returned the
;; same thing — filtering an out-of-order ledger produces an out-of-order
;; history and nothing complains.
;; ---------------------------------------------------------------------------

(defn claim-history
  "Every ledger entry for `claim-id`, oldest first — the whole life of one
  claim (escalated, then committed; or held), not just its latest state.

  Returns `[]` for a claim id nobody has heard of. That is NOT the same as a
  claim with no verdict, and callers must not render it as one: an empty
  history means the actor has no record of this claim at all.

  A nil `claim-id` returns nil rather than every entry that happens to carry
  no claim id. Matching nil against nil is how a lookup for `nothing` quietly
  becomes a lookup for `everything`."
  [store claim-id]
  (when (some? claim-id)
    (filterv #(= claim-id (:claim-id %)) (ledger store))))

(defn ledger-of
  "Every ledger entry belonging to `employee-id`, oldest first. Same nil rule
  as `claim-history`, for the same reason — and here the consequence of
  getting it wrong is one employee reading the whole company's spending."
  [store employee-id]
  (when (some? employee-id)
    (filterv #(= employee-id (:employee-id %)) (ledger store))))
