(ns keihi.actor
  "KeihiActor — 経費精算 as a `langgraph.graph/state-graph` (ADR-2607011000 /
  CLAUDE.md Actors section). One graph run = one expense-claim request
  (intake → advise → govern → decide → commit / hold), with a human-approval
  interrupt for escalated proposals. No infinite internal loop; checkpointed
  per superstep, so an interrupted run resumes after sign-off.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                        (:ok? true)
                                            +-> :escalate -> :request-approval (:escalate?, interrupt-before)
                                            +-> :hold                          (:hard? true)
  ```

  The unconditional invariant: the KeihiAdvisor can never disburse a yen the
  KeihiGovernor refuses. Every `commit-record!` is gated behind `:decide`, and
  `:decide` reads only the verdict.

  Every disposition appends to the ledger — a hold is a fact about what the
  actor was asked to do, and an audit trail that only records the operations
  that succeeded is an audit trail of the wrong thing.

  `:escalate` exists for the third of those. It is not a terminal node and it
  decides nothing; it sits between `:decide` and the interrupt so that a claim
  waiting on a human signature is a claim the ledger has heard of. Without it,
  `awaiting approval` and `never submitted` were the same observation — both
  produced zero ledger entries — and any surface reading the ledger had to
  report an escalated claim as unknown. The interrupt is `interrupt-before
  :request-approval`, so resuming restarts at `:request-approval` and this node
  appends exactly once, at the moment the run stops.

  Every ledger fact carries `:claim-id` and `:employee-id` at the top level, on
  all three dispositions. Nesting the identity inside `:record` would have made
  a committed claim findable and a held one not — the held claim being the one
  somebody needs to look up."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [keihi.advisor :as advisor]
            [keihi.governor :as governor]
            [keihi.store :as store]))

(defn- identify
  "Stamp a ledger fact with the identity of the claim it is about.

  Applied to every disposition through one function rather than three literal
  maps, so a fourth disposition cannot be added without an identity — the way
  `:hold` originally shipped without one."
  [request fact]
  (assoc fact
         :claim-id (:claim-id request)
         :employee-id (:employee-id request)))

(defn decide
  "Which disposition a verdict routes to.

  `:hard?` is tested FIRST, and it stays first: it is what kept the one
  drifted governor in the fleet from actually mis-routing, so a malformed
  verdict from anywhere still fails closed.

  Extracted from the `:decide` node rather than left inline, because inline
  it could not be handed a verdict this governor cannot produce.
  `governor.core/verdict` makes `:hard?` and `:escalate?` mutually exclusive,
  so through the graph the ORDER of the two clauses is unobservable — and a
  mutation swapping them survived the entire suite on 2026-08-18. The clause
  order defends against a governor that has drifted, and the only way to
  measure that defence is to give it the drift."
  [verdict]
  (cond
    (:hard? verdict) :hold
    (:escalate? verdict) :request-approval
    :else :commit))

(defn build-graph
  "Build a compiled KeihiActor graph. `store` implements `keihi.store/Store`.
  `advisor` implements `keihi.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                  (fn [{:keys [request]}]
                    (let [p (advisor/-advise advisor store request)]
                      {:proposal p
                       :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                  (fn [{:keys [request context proposal]}]
                    (let [v (governor/check request context proposal store)]
                      {:verdict v
                       :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                  (fn [{:keys [verdict]}] {:disposition (decide verdict)}))
      (g/add-node :escalate
                  (fn [{:keys [request verdict]}]
                    (store/append-ledger! store
                                          (identify request
                                                    {:disposition :request-approval
                                                     :verdict verdict}))
                    {:audit [{:node :escalate :verdict verdict}]}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                  (fn [{:keys [request proposal verdict]}]
                    (let [record {:claim-id (:claim-id request)
                                  :employee-id (:employee-id request)
                                  :op (:op proposal)
                                  :receipt (:receipt proposal)
                                  :amount (:amount proposal)
                                  :payload proposal}]
                      (store/commit-record! store record)
                      ;; the ledger carries the verdict, not just the record:
                      ;; `what was paid` without `what was checked` cannot be
                      ;; audited after the fact.
                      (store/append-ledger! store
                                            (identify request
                                                      {:disposition :commit
                                                       :record record
                                                       :verdict verdict}))
                      {:record record
                       :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                  (fn [{:keys [request verdict]}]
                    (store/append-ledger! store
                                          (identify request {:disposition :hold
                                                             :verdict verdict}))
                    {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :escalate
           :hold)))
      (g/add-edge :escalate :request-approval)
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one claim request to completion or interrupt. `thread-id` scopes
  checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node advances
  straight to `:commit` (approval IS the act of resuming the thread).

  There is deliberately no `approve!` path out of `:hold` — a HARD hold has no
  approval route, and adding one here would be the drift `kotoba-lang/governor`
  exists to prevent, moved from the verdict into the graph."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
