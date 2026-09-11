(ns keihi.handoff
  "What happened to the journal entry — recorded, so that *converted* stops
  meaning *posted*.

  `keihi.shiwake` turns an approved claim into a `:draft-entry` request.
  Something else carries it to `cloud-itonami-isco-4311`, which can post it,
  find it already posted, hold it, escalate it or refuse it — **and until
  this namespace existed, all five looked identical from here.** There was no
  trace at all. A step that cannot fail visibly is the defect this plane
  keeps finding, and the hand-off was one.

  ## Pure

  It takes a response something else obtained. No HTTP, no client, no
  reference to 4311 — asserted by a test that scans this source. Carrying the
  request is not this actor's job; recording what came back is.

  ## The good outcome is recorded too

  A ledger that only wrote down refusals could not answer *was this claim
  posted?*, which is the question the loop exists to close.

  ## `:duplicate` is not `:posted`

  4311's posting ids are content-addressed and its commit is idempotent, so a
  re-sent entry answers `:duplicate?`. That is a different fact from having
  written: one says *I wrote this*, the other says *this was already there*.
  Folding them would make a reconciliation unable to distinguish a carrier
  that retried from one that double-submitted work nobody had confirmed."
  (:require [kotoba.lang.text :as str]))

(def outcomes
  "Every outcome this namespace will name. `:unknown-response` is one of
  them on purpose — a response shape nobody anticipated is a fact about the
  hand-off, not an absence of one."
  #{:posted :duplicate :held :awaiting-approval :rejected :unknown-response})

(defn- classify [{:keys [status body]}]
  (cond
    (and (= 200 status) (true? (:duplicate? body))) :duplicate
    (= 200 status) :posted
    (= 409 status) :held
    (= 202 status) :awaiting-approval
    (#{400 403 503} status) :rejected
    :else :unknown-response))

(defn fact
  "One 4311 response as the ledger fact this actor appends.

  `claim` is whatever identifies the claim on this side — it is carried
  through untouched, because a reconciliation record that cannot be joined
  back to the thing it reconciles is not one.

  An unrecognised response yields `:unknown-response` and keeps the status
  and body, so the shape can be diagnosed. **It never defaults to posted.**
  Defaulting the unrecognised case to success is how a hand-off reports a
  clean run over entries nobody accepted."
  [claim response]
  (let [outcome (classify response)]
    (cond-> {:disposition :handoff
             :handoff/outcome outcome
             :handoff/claim claim
             :handoff/status (:status response)}
      (:posting (:body response))
      (assoc :handoff/posting (:posting (:body response)))

      (seq (:violations (:body response)))
      (assoc :handoff/violations (mapv #(select-keys % [:rule :detail])
                                       (:violations (:body response))))

      (:error (:body response))
      (assoc :handoff/error (:error (:body response)))

      ;; Only for the case nobody anticipated, and only then: keeping every
      ;; body on every fact would put a copy of the ledger in the ledger.
      (= :unknown-response outcome)
      (assoc :handoff/response (select-keys response [:status :body])))))

(defn facts
  "Pair `requests` with the `results` 4311 returned for them, in order.

  Returns `{:facts [...]}`, or `{:error :length-mismatch …}` and **no facts
  at all** when the counts differ. 4311 returns results in submission order,
  so a mismatch means something is wrong upstream and pairing by position
  would misattribute every outcome — silently, and in a record whose whole
  purpose is attribution. Refusing is the only honest answer.

  `results` is the `:results` vector from `POST /api/entries`, whose entries
  carry `:outcome` directly. It is used, rather than re-derived from
  `:status`, because 4311 already decided and two classifications that agree
  until they don't is worse than one."
  [requests results]
  (if (not= (count requests) (count results))
    {:error :length-mismatch
     :requests (count requests)
     :results (count results)
     :why "results are positional; pairing a mismatch would misattribute every outcome"}
    {:facts
     (mapv (fn [req {:keys [outcome status posting violations error]}]
             (cond-> {:disposition :handoff
                      :handoff/outcome (if (contains? outcomes outcome)
                                         outcome
                                         :unknown-response)
                      :handoff/claim (:shiwake/claim req)
                      :handoff/status status}
               posting (assoc :handoff/posting posting)
               (seq violations) (assoc :handoff/violations
                                       (mapv #(select-keys % [:rule :detail]) violations))
               error (assoc :handoff/error error)
               (not (contains? outcomes outcome))
               (assoc :handoff/reported-outcome outcome)))
           requests results)}))

(defn unresolved
  "The facts that mean somebody has to do something: everything except
  `:posted` and `:duplicate`.

  Named as a question rather than a filter over a status code, because the
  set is the point — an operator's queue is exactly this."
  [fs]
  (vec (remove #(#{:posted :duplicate} (:handoff/outcome %)) fs)))
