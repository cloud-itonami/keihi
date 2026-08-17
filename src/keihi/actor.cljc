(ns keihi.actor
  "KeihiActor — 経費精算 as a `langgraph.graph/state-graph` (ADR-2607011000 /
  CLAUDE.md Actors section). One graph run = one expense-claim request
  (intake → advise → govern → decide → commit / hold), with a human-approval
  interrupt for escalated proposals. No infinite internal loop; checkpointed
  per superstep, so an interrupted run resumes after sign-off.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit           (:ok? true)
                                            +-> :request-approval (:escalate?, interrupt-before)
                                            +-> :hold             (:hard? true)
  ```

  The unconditional invariant: the KeihiAdvisor can never disburse a yen the
  KeihiGovernor refuses. Every `commit-record!` is gated behind `:decide`, and
  `:decide` reads only the verdict.

  Both terminal nodes append to the ledger — a hold is a fact about what the
  actor was asked to do, and an audit trail that only records the operations
  that succeeded is an audit trail of the wrong thing."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [keihi.advisor :as advisor]
            [keihi.governor :as governor]
            [keihi.store :as store]))

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
                  (fn [{:keys [verdict]}]
                    ;; `:hard?` first, and it stays first: it is what kept the
                    ;; one drifted governor in the fleet from actually
                    ;; mis-routing, so a malformed verdict from anywhere still
                    ;; fails closed.
                    {:disposition (cond
                                    (:hard? verdict) :hold
                                    (:escalate? verdict) :request-approval
                                    :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                  (fn [{:keys [request proposal verdict]}]
                    (let [record {:employee-id (:employee-id request)
                                  :op (:op proposal)
                                  :receipt (:receipt proposal)
                                  :amount (:amount proposal)
                                  :payload proposal}]
                      (store/commit-record! store record)
                      ;; the ledger carries the verdict, not just the record:
                      ;; `what was paid` without `what was checked` cannot be
                      ;; audited after the fact.
                      (store/append-ledger! store {:disposition :commit
                                                   :record record
                                                   :verdict verdict})
                      {:record record
                       :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                  (fn [{:keys [verdict]}]
                    (store/append-ledger! store {:disposition :hold
                                                 :verdict verdict})
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
           :request-approval :request-approval
           :hold)))
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
