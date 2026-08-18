(ns keihi.edge.endpoints
  "The HTTP surface keihi exposes — exactly three routes:

      POST /api/claim        submit an expense claim
      GET  /api/claim/:id    the whole life of one claim, and its verdict
      GET  /api/ledger       the caller's own slice of the append-only ledger

  and nothing else. Per `manifest/repository-rules.edn` an itonami actor is
  `:on-demand`: it answers a request and stops.

  Portable `.cljc` request→response functions. No host effects, no framework,
  no platform types — a response is a map `{:status n :body {...}}` and the
  caller's DID arrives already verified. CACAO verification is
  `kotoba-lang/org-chainagnostic-cacao`'s job and is not reimplemented here
  (ADR-2607268000); mounting these functions on Cloudflare Pages Functions is a
  host binding this repo does not yet carry, and inventing an untested one
  would be worse than saying so.

  ## Why these three, and why not the other three ops

  Of keihi's four ops only `:submit-claim` both needs a network path and ends
  anywhere other than a human's desk:

    :submit-claim  the phone that photographed the receipt has to reach the
                   actor, and a clean claim auto-commits            → exposed
    :disburse      ALWAYS escalates; real money leaves the company  → not exposed
    :amend-claim   rewrites a submitted figure                      → not exposed
    :reject-claim  a decision about someone's money, made by a person
                                                                    → not exposed

  `submit-claim-core!` hard-codes `:op :submit-claim`. It does not read an op
  out of the body, so a request naming `:disburse` submits a claim — it cannot
  reach the money-moving path by asking to. The employee id comes from the
  verified DID for the same reason, never from the body.

  ## Three gates, and none of them is optional

  1. CACAO signature + temporal window — the host's, before these functions.
  2. The verified DID must be on the allow-list, which maps DID → employee id.
     **An absent allow-list serves 503, never an open endpoint.** An open claim
     endpoint is an open write path into the company's disbursement record.
  3. Reads are scoped to the caller's own employee id. Knowing a claim id is
     not enough to read someone else's claim — the same property the governor's
     `:receipt-wrong-employee` rule enforces on the write side.

  ## No `:ok` boolean on the claim routes

  A claim has three outcomes and a boolean has two. `submit-claim-core!`
  reports `:disposition` (`:commit` / `:request-approval` / `:hold`) with the
  HTTP status to match (200 / 202 / 409), because collapsing
  `awaiting a human signature` into either `paid` or `refused` is a lie in
  whichever direction it is told. `:ok` survives only on the responses that are
  about the REQUEST rather than about the claim (503 / 403 / 400 / 404 / 405),
  where there is genuinely nothing three-valued to report.

  For the same reason every claim response carries `:tax` — the coverage
  summary of what the statutes could and could not say. A 200 that did not say
  so would render `we read 消費税法 and this claim satisfies it` and `nobody has
  read the law where this employee works` as the same green tick."
  (:require [keihi.actor :as actor]
            [keihi.store :as store]
            [keihi.handoff :as handoff]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

;; ---------------------------------------------------------------------------
;; Allow-list
;; ---------------------------------------------------------------------------

(defn parse-allowlist
  "`\"did:key:z6Mk…=emp-1,did:key:z6Ml…=emp-2\"` -> `{did employee-id}`, or nil
  when absent, blank or wholly malformed — `nobody is allowed` and `nothing was
  configured` are different deployment states and get different status codes."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did emp] (map #(.trim %) (.split entry "="))]
                          (when (and did emp (seq did) (seq emp))
                            [did emp])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn employee-for [allowlist did] (get allowlist did))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `KEIHI_STORE` env var.

    nil          nothing configured
    :ephemeral   `MemStore` — does not survive the request
    :datomic     `DatomicStore` over langchain.db

  Returns nil for anything else, including an unrecognised value — a typo in a
  deployment variable must not silently select a storage mode.

  Portable (takes a plain map) so the decision is testable without a platform."
  [env]
  (case (some-> (get env "KEIHI_STORE") .trim)
    "ephemeral" :ephemeral
    "datomic" :datomic
    nil))

(defn store-for
  "A store for `mode`, or nil when nothing is configured."
  [mode]
  (case mode
    :ephemeral (store/mem-store)
    :datomic (store/datomic-store)
    nil))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store makes
  every request fail the governor's registration check, so the caller is told
  `:no-employee` — blamed for a deployment that has no store at all.
  Misattributed blame is worse than a refusal: the operator goes looking at
  their own registration while the actual fault is here."
  []
  {:status 503
   :body {:ok false :error "no store configured"
          :hint (str "set KEIHI_STORE=datomic for the langchain.db backend,"
                     " or KEIHI_STORE=ephemeral for a non-persisting smoke test")}})

;; ---------------------------------------------------------------------------
;; Body parsing — structure only
;; ---------------------------------------------------------------------------

(defn- money? [x] (and (number? x) (not (neg? x))))

(defn parse-claim-body
  "EDN request body -> the fields the edge will forward to the actor, or nil.

  Read with `clojure.edn/read-string`, which evaluates nothing.

  This validates STRUCTURE and nothing else. Whether a claim is admissible is
  the governor's question, and pre-empting it here would replace ten cited
  invariants with an uncited one — a claim citing no receipt must come back as
  `:no-receipt` with 消費税法 attached, not as a flat 400.

  Three things are structural and therefore checked:

    :claim-id  a non-blank string. Required, because a claim nobody can name
               is a claim nobody can look up afterwards, and this actor will
               not mint an identity on the caller's behalf and hand it back as
               though the caller had chosen it.
    :amount    a non-negative number when present. Absent is left to the
               governor (`nil ≠ 5000` is its arithmetic rule firing correctly).
    :lines     a vector of maps whose `:amount`s are non-negative numbers.
               Not politeness: the governor sums them with `+`, so a line
               carrying \"5000\" throws inside the graph and a structural
               error surfaces as a 500."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (and (map? m)
                 (string? (:claim-id m)) (seq (.trim (:claim-id m)))
                 (or (nil? (:amount m)) (money? (:amount m)))
                 (or (nil? (:lines m))
                     (and (vector? (:lines m))
                          (every? map? (:lines m))
                          (every? (comp money? :amount) (:lines m)))))
        (select-keys m [:claim-id :receipt :amount :lines :tax-treatment
                        :preservation-exception :stake])))
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; What the statutes could and could not say
;; ---------------------------------------------------------------------------

(defn tax-coverage
  "The verdict's `:tax` report reduced to one coverage keyword per statute, for
  a response body.

  Every value is descriptive and none of them reads as approval:

    :not-assessed  the op asserts no expense, or no receipt was read, so the
                   article was never reached
    :not-claimed   no 仕入税額控除 was sought, so 第三十条 raises no question
    :none          nobody has read the statute for this jurisdiction
    :not-declared  the document is silent about what the article turns on
    :checked       the article was applied; the verdict says with what result

  `:none` is the one that matters. It is not a pass and it is not a refusal,
  and a surface that printed nothing for it would show an unchecked
  jurisdiction exactly the way it shows a satisfied one.

  ## `:limits` and `:retention` — because `:checked` is not `valid`

  One keyword per statute was enough while `[:jp]` was the only jurisdiction
  whose facets were read. It stopped being enough at taxlaw@d2663b54: a
  `:credit :checked` for `[:eu]` means the ISO 3166 alpha-2 PREFIX of the VAT
  identification number matched, and Article 215 gives nothing else — the
  body is Member State law nobody read. Printing `:checked` and stopping
  would tell an operator the VAT number is valid, which is strictly more than
  was measured, and is the same failure as printing nothing for `:none`.

  So the verdict's `:limits` come through whole (each names the facet, what
  was checked, what was not, and why), and so does `:retention` — where nil
  years is the instrument's answer for `[:eu]` and `[:us]` and NOT missing
  data. Both are computed once in the governor and carried, never re-derived
  here; a surface that recomputed them would drift from the hold it is
  displaying."
  [verdict]
  (let [tax (:tax verdict)]
    {:assessed (some? tax)
     :credit (or (:taxlaw/coverage (:credit tax)) :not-claimed)
     :preservation (or (:taxlaw/coverage (:preservation tax)) :not-assessed)
     :invoice-preservation (or (:keihi/coverage (:invoice-preservation tax))
                               :not-assessed)
     :limits (vec (:limits tax))
     :retention (:retention tax)}))

(defn- violation-summary [verdict]
  (mapv #(select-keys % [:rule :detail]) (:violations verdict)))

;; ---------------------------------------------------------------------------
;; POST /api/claim
;; ---------------------------------------------------------------------------

(defn submit-claim-core!
  "`POST /api/claim`. `caller-did` is already verified.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, no `:claim-id`, or a non-numeric amount
    200  committed        (`:disposition :commit`)
    202  awaiting a human (`:disposition :request-approval`) — accepted and
         recorded in the ledger, NOT paid. 202 rather than 200 because the
         claim's outcome is still open, and rather than 409 because nothing
         was refused.
    409  held             (`:disposition :hold`), with the violations

  The op is `:submit-claim` unconditionally and the employee comes from the
  DID. Neither is read from the body."
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employee-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (if-let [body (parse-claim-body raw-body)]
      (let [employee-id (employee-for allowlist caller-did)
            claim-id (:claim-id body)
            g (actor/build-graph {:store store})
            r (actor/run-request! g (assoc body
                                           :employee-id employee-id
                                           :op :submit-claim)
                                  {} (str "edge-" employee-id "-" claim-id))
            verdict (get-in r [:state :verdict])
            disposition (get-in r [:state :disposition])
            base {:claim-id claim-id :employee employee-id :store mode
                  :disposition disposition :tax (tax-coverage verdict)}]
        (case disposition
          :commit {:status 200
                   :body (assoc base :amount (get-in r [:state :record :amount])
                                :receipt (get-in r [:state :record :receipt]))}
          :request-approval {:status 202
                             :body (assoc base
                                          :awaiting :human-approval
                                          :reason (:escalation-reason verdict))}
          {:status 409 :body (assoc base :violations (violation-summary verdict))}))
      {:status 400 :body {:ok false :error "invalid request body"}})))

;; ---------------------------------------------------------------------------
;; GET /api/claim/:id
;; ---------------------------------------------------------------------------

(defn claim-verdict-core
  "`GET /api/claim/:id`. The whole life of one claim, oldest entry first.

    503  no allow-list configured
    403  caller not on the allow-list
    400  blank claim id
    404  no claim under that id is visible to this caller
    200  the ledger entries for it, and the latest disposition

  A claim belonging to ANOTHER employee returns the same 404 as one that never
  existed, deliberately. A 403 there would confirm the claim exists, and a
  claim id would become something a stranger can probe for. The two cases are
  indistinguishable to the caller and distinguishable in the ledger, which is
  the right way round.

  An unknown claim is never an empty 200. `no entries` and `no such claim` are
  the same bytes if the answer is a list, and only one of them means the actor
  knows anything about it."
  [store allowlist caller-did claim-id]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employee-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    (not (and (string? claim-id) (seq (.trim claim-id))))
    {:status 400 :body {:ok false :error "missing claim id"}}

    :else
    (let [employee-id (employee-for allowlist caller-did)
          history (filterv #(= employee-id (:employee-id %))
                           (store/claim-history store claim-id))]
      (if (empty? history)
        {:status 404 :body {:ok false :error "no such claim for this caller"}}
        (let [latest (peek history)]
          {:status 200
           :body {:claim-id claim-id
                  :employee employee-id
                  :disposition (:disposition latest)
                  :tax (tax-coverage (:verdict latest))
                  :violations (violation-summary (:verdict latest))
                  :history (mapv #(hash-map :disposition (:disposition %)
                                            :amount (get-in % [:record :amount]))
                                 history)}})))))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(defn ledger-core
  "`GET /api/ledger`. The caller's OWN slice of the append-only ledger, oldest
  first — every disposition, commit and escalation and hold alike.

    503  no allow-list configured
    403  caller not on the allow-list
    200  the caller's entries, possibly none

  The whole ledger has no HTTP representation. It is every employee's spending,
  and an actor that hands that to whoever holds one valid DID has published the
  company's books. An employee with no entries is a complete answer and gets
  200 with an empty vector — unlike an unknown claim id, `you have filed
  nothing` is something this actor actually knows.

  `:scope` is on the body so nobody mistakes this for the whole ledger."
  [store allowlist caller-did]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employee-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [employee-id (employee-for allowlist caller-did)
          entries (store/ledger-of store employee-id)]
      {:status 200
       :body {:employee employee-id
              :scope :caller-only
              :count (count entries)
              :entries (mapv (fn [e]
                               {:claim-id (:claim-id e)
                                :disposition (:disposition e)
                                :amount (get-in e [:record :amount])
                                :tax (tax-coverage (:verdict e))
                                :violations (violation-summary (:verdict e))})
                             entries)}})))

;; ---------------------------------------------------------------------------
;; The surface itself
;; ---------------------------------------------------------------------------

(def claim-path-prefix "/api/claim/")

(def max-handoffs
  "A cap, for the same reason the ledger actor caps its batch: an uncapped
  body is a way to hold this actor for an unbounded time on one request. 200
  is a number, not a measurement, and it is reported in the refusal so a
  caller learns the limit from the 400 rather than from a timeout."
  200)

(defn record-handoff-core!
  "`POST /api/handoff`. Where the carrier brings back what the ledger actor
  said.

  `keihi.handoff` turns a response into a fact and is pure — it makes no
  call, deliberately, because reaching into another actor's ledger is the
  actuation this repo refuses. That left it reachable from nothing: the
  namespace existed, the facts existed, and no path produced one. This is
  that path, and it runs the right way round — **the carrier posts the
  outcome here**, rather than this actor going out to fetch it.

  ## Explicit pairs, not positions

  The body is `{:handoffs [{:claim {…} :response {…}} …]}`. Each pair names
  its own claim, so **this route cannot misattribute by position at all** —
  the failure `handoff/facts` has to refuse a length mismatch to avoid does
  not arise here, because nothing is being lined up. Where a carrier can
  send pairs, pairs are strictly better than order.

    503  no allow-list / no store configured
    403  caller not on the allow-list
    400  the body is not a non-empty vector of pairs, or is over `max-handoffs`
    200  every fact appended, with the unresolved queue

  ## It appends, and reports what still needs somebody

  Every outcome is written, including the good ones, because a ledger that
  recorded only refusals could not answer *was this claim posted?*. The
  response then carries `:unresolved` — everything that is not `:posted` or
  `:duplicate` — so the carrier learns, in the same round trip, which claims
  a human still has to look at."
  [store allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employee-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [parsed (try (edn/read-string raw-body)
                      (catch #?(:clj Exception :cljs :default) _ nil))
          pairs (:handoffs parsed)]
      (cond
        (not (and (map? parsed) (vector? pairs) (seq pairs)))
        {:status 400 :body {:ok false :error "body must be {:handoffs [{:claim … :response …} …]}"}}

        (> (count pairs) max-handoffs)
        {:status 400 :body {:ok false :error "too many handoffs"
                            :max max-handoffs :submitted (count pairs)}}

        (not (every? #(and (map? %) (contains? % :claim) (map? (:response %))) pairs))
        {:status 400 :body {:ok false :error "each handoff needs a :claim and a map :response"}}

        :else
        (let [facts (mapv #(handoff/fact (:claim %) (:response %)) pairs)]
          (doseq [f facts] (store/append-ledger! store f))
          {:status 200
           :body {:ok true
                  :employee (employee-for allowlist caller-did)
                  :recorded (count facts)
                  :summary (frequencies (map :handoff/outcome facts))
                  :unresolved (handoff/unresolved facts)}})))))

(defn route
  "The whole surface, as data in and data out.

  `request` is `{:method :get|:post :path \"/api/…\" :body \"…\"}`. Returns
  `{:status n :body {...}}`.

  `store` and `mode` are passed IN rather than built here. A dispatcher that
  called `(store-for mode)` per request would give every read an empty store —
  the write route would work, both read routes would answer 404 and an empty
  ledger forever, and the deployment would look healthy while remembering
  nothing. The host owns the store's lifetime; this function owns the routing.

  A nil `mode` still serves 503 here rather than at the host, so the decision
  not to guess a storage backend is testable without a platform.

  Having one dispatcher rather than three exported handlers is what makes
  `there are exactly three routes` a testable claim instead of a sentence in a
  README. A path nobody declared is 404 and a method nobody declared is 405 —
  distinct, because `POST /api/ledger` is a caller using the wrong verb on a
  real route and `POST /api/payroll` is a caller inventing one."
  [store mode allowlist caller-did {:keys [method path body]}]
  (if (or (nil? mode) (nil? store))
    (store-unconfigured-response)
    (cond
      (= path "/api/claim")
      (if (= method :post)
        (submit-claim-core! store mode allowlist caller-did body)
        {:status 405 :body {:ok false :error "method not allowed" :allow [:post]}})

      (= path "/api/handoff")
      (if (= method :post)
        (record-handoff-core! store allowlist caller-did body)
        {:status 405 :body {:ok false :error "method not allowed" :allow [:post]}})

      (= path "/api/ledger")
      (if (= method :get)
        (ledger-core store allowlist caller-did)
        {:status 405 :body {:ok false :error "method not allowed" :allow [:get]}})

      (and (string? path) (.startsWith path claim-path-prefix))
      (if (= method :get)
        (claim-verdict-core store allowlist caller-did
                            (subs path (count claim-path-prefix)))
        {:status 405 :body {:ok false :error "method not allowed" :allow [:get]}})

      :else
      {:status 404 :body {:ok false :error "no such route"}})))
