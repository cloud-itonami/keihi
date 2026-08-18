(ns keihi.edge.endpoints-test
  "The HTTP surface, as data in and data out.

  Two things are being tested and they are not the same thing. That the
  governor refuses the right claims is `keihi.governor-test`'s job. What has to
  live HERE is that the edge does not answer a question the governor was
  supposed to answer, does not let the body choose who is claiming or what
  operation is being performed, and does not report `nobody looked` and `we
  looked and it was fine` with the same body."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.edge.endpoints :as edge]))

(def ^:private allowlist
  {"did:key:zAlice" "emp-1" "did:key:zBob" "emp-2"})

(defn- seeded
  ([] (seeded store/mem-store :jp))
  ([make jurisdiction]
   (doto (make)
     (store/register-employee! {:employee-id "emp-1" :name "田中花子"
                                :jurisdiction jurisdiction})
     (store/register-employee! {:employee-id "emp-2" :name "佐藤太郎"
                                :jurisdiction jurisdiction})
     (store/register-receipt! {:receipt-id "rc-1" :employee-id "emp-1"
                               :kind :invoice-received :amount 5000
                               :registration-number "T1234567890123"
                               :origin :paper :preservation :paper})
     (store/register-receipt! {:receipt-id "rc-2" :employee-id "emp-2"
                               :kind :invoice-received :amount 5000
                               :registration-number "T1234567890123"
                               :origin :paper :preservation :paper}))))

(defn- body [& {:as overrides}]
  (pr-str (merge {:claim-id "c-1" :receipt "rc-1" :amount 5000
                  :lines [{:purpose "客先訪問の交通費" :amount 5000}]}
                 overrides)))

(defn- submit [st did b]
  (edge/submit-claim-core! st :ephemeral allowlist did b))

;; ---------------------------------------------------------------------------
;; The two gates
;; ---------------------------------------------------------------------------

(deftest an-absent-allowlist-serves-503-on-every-route
  (testing "an open claim endpoint is an open write path into the company's
            disbursement record, and an open read is its books"
    (let [st (seeded)]
      (is (= 503 (:status (edge/submit-claim-core! st :ephemeral nil "did:key:zAlice" (body)))))
      (is (= 503 (:status (edge/claim-verdict-core st nil "did:key:zAlice" "c-1"))))
      (is (= 503 (:status (edge/ledger-core st nil "did:key:zAlice")))))))

(deftest parse-allowlist-distinguishes-empty-from-absent
  (is (nil? (edge/parse-allowlist nil)))
  (is (nil? (edge/parse-allowlist "  ")))
  (is (nil? (edge/parse-allowlist "no-equals-sign")))
  (is (= {"did:key:zAlice" "emp-1"} (edge/parse-allowlist "did:key:zAlice=emp-1")))
  (is (= 2 (count (edge/parse-allowlist "did:key:zAlice=emp-1, did:key:zBob=emp-2")))))

(deftest an-unlisted-caller-is-refused-on-every-route
  (let [st (seeded)]
    (is (= 403 (:status (submit st "did:key:zMallory" (body)))))
    (is (= 403 (:status (edge/claim-verdict-core st allowlist "did:key:zMallory" "c-1"))))
    (is (= 403 (:status (edge/ledger-core st allowlist "did:key:zMallory"))))))

;; ---------------------------------------------------------------------------
;; What the body may not decide
;; ---------------------------------------------------------------------------

(deftest the-employee-comes-from-the-did-not-the-body
  (let [st (seeded)
        r (submit st "did:key:zBob" (body :employee-id "emp-1" :receipt "rc-2"))]
    (is (= 200 (:status r)))
    (is (= "emp-2" (get-in r [:body :employee])))
    (testing "nothing landed under emp-1 despite the body naming it"
      (is (empty? (store/records-of st "emp-1"))))))

(deftest the-body-cannot-choose-the-op-and-so-cannot-reach-the-money
  (testing ":disburse always escalates; a body asking for it submits a claim
            instead, and the committed record says so"
    (let [st (seeded)
          r (submit st "did:key:zAlice" (body :op :disburse))]
      (is (= 200 (:status r)))
      (is (= :commit (get-in r [:body :disposition])))
      (is (= [:submit-claim] (mapv :op (store/records-of st "emp-1")))))))

;; ---------------------------------------------------------------------------
;; Structure is the edge's question; admissibility is the governor's
;; ---------------------------------------------------------------------------

(deftest structurally-broken-bodies-are-400
  (doseq [bad ["" "((" "{:receipt \"rc-1\"}"
               (pr-str {:claim-id "" :amount 5000})
               (pr-str {:claim-id "c-1" :amount "5000"})
               (pr-str {:claim-id "c-1" :amount -1})
               (pr-str {:claim-id "c-1" :amount 5000 :lines [{:amount "5000"}]})
               (pr-str {:claim-id "c-1" :amount 5000 :lines "not-a-vector"})]]
    (is (= 400 (:status (submit (seeded) "did:key:zAlice" bad)))
        (str "should reject " (pr-str bad)))))

(deftest a-non-numeric-line-would-have-thrown-inside-the-graph
  (testing "the governor sums :lines with +, so this is structure, not policy"
    (is (= "invalid request body"
           (get-in (submit (seeded) "did:key:zAlice"
                           (pr-str {:claim-id "c-1" :amount 5000
                                    :lines [{:amount :five-thousand}]}))
                   [:body :error])))))

(deftest a-claim-citing-no-receipt-is-the-governors-refusal-not-a-400
  (testing "flattening it to 400 would replace a cited invariant with an
            uncited one"
    (let [r (submit (seeded) "did:key:zAlice" (body :receipt nil))]
      (is (= 409 (:status r)))
      (is (= :hold (get-in r [:body :disposition])))
      (is (= [:no-receipt] (mapv :rule (get-in r [:body :violations]))))
      (is (seq (:detail (first (get-in r [:body :violations]))))
          "the refusal says why, not merely that"))))

(deftest a-claim-that-does-not-add-up-is-409-and-writes-nothing
  (let [st (seeded)
        r (submit st "did:key:zAlice" (body :amount 6000))]
    (is (= 409 (:status r)))
    (is (= [:amount-mismatch] (mapv :rule (get-in r [:body :violations]))))
    (is (empty? (store/records-of st "emp-1")))))

;; ---------------------------------------------------------------------------
;; Three outcomes, three statuses
;; ---------------------------------------------------------------------------

(deftest a-clean-claim-commits
  (let [st (seeded)
        r (submit st "did:key:zAlice" (body))]
    (is (= 200 (:status r)))
    (is (= :commit (get-in r [:body :disposition])))
    (is (= 5000 (get-in r [:body :amount])))
    (is (= 1 (count (store/records-of st "emp-1"))))))

(deftest an-asserted-proviso-is-202-and-pays-nothing
  (testing "第三十条第七項's ただし書 requires the BUSINESS to 証明 it. 202 rather
            than 200 because the outcome is still open, and rather than 409
            because nothing was refused."
    (let [st (seeded)
          _ (store/register-receipt! st {:receipt-id "rc-gone" :employee-id "emp-1"
                                         :kind :invoice-received :amount 5000
                                         :registration-number "T1234567890123"
                                         :origin :paper :preservation :none})
          r (submit st "did:key:zAlice"
                    (body :receipt "rc-gone" :tax-treatment :input-tax-credit
                          :preservation-exception {:kind :disaster}))]
      (is (= 202 (:status r)))
      (is (= :request-approval (get-in r [:body :disposition])))
      (is (= :human-approval (get-in r [:body :awaiting])))
      (is (some? (get-in r [:body :reason])))
      (testing "nothing is written while the approval is pending, but the
                ledger has heard of the claim"
        (is (empty? (store/records-of st "emp-1")))
        (is (= [:request-approval]
               (mapv :disposition (store/claim-history st "c-1"))))))))

(deftest no-response-carries-an-ok-boolean-for-the-claim-itself
  (testing "a claim has three outcomes and a boolean has two"
    (doseq [[label r] {:commit (submit (seeded) "did:key:zAlice" (body))
                       :hold (submit (seeded) "did:key:zAlice" (body :amount 1))}]
      (testing (str label)
        (is (not (contains? (:body r) :ok)))
        (is (contains? (:body r) :disposition))))))

;; ---------------------------------------------------------------------------
;; Unknown is never a pass
;; ---------------------------------------------------------------------------

(deftest every-claim-response-says-what-the-statutes-could-not-say
  (doseq [[label r] {:commit (submit (seeded) "did:key:zAlice" (body))
                     :hold (submit (seeded) "did:key:zAlice" (body :amount 1))}]
    (testing (str label)
      (is (map? (get-in r [:body :tax]))
          "a 200 with no coverage report is a green tick over an unread statute"))))

(deftest an-unchecked-jurisdiction-reports-none-and-not-silence
  (let [st (seeded store/mem-store :atlantis)
        r (submit st "did:key:zAlice" (body :tax-treatment :input-tax-credit))]
    (is (= 409 (:status r)))
    (is (= [:unchecked-jurisdiction] (mapv :rule (get-in r [:body :violations]))))
    (is (= :none (get-in r [:body :tax :credit])))
    (is (= :none (get-in r [:body :tax :invoice-preservation])))))

(deftest not-claimed-not-assessed-and-checked-are-three-different-answers
  (testing "collapsing any pair of them is the distinction these libraries
            exist to preserve"
    (let [no-credit (get-in (submit (seeded) "did:key:zAlice" (body)) [:body :tax])
          no-receipt (get-in (submit (seeded) "did:key:zAlice" (body :receipt nil))
                             [:body :tax])]
      (testing "a claim that sought no 仕入税額控除 raises no question under 第三十条"
        (is (true? (:assessed no-credit)))
        (is (= :not-claimed (:credit no-credit)))
        (is (= :no-claim (:invoice-preservation no-credit))))
      (testing "a claim with no receipt read nothing at all"
        (is (false? (:assessed no-receipt)))
        (is (= :not-assessed (:preservation no-receipt)))
        (is (= :not-assessed (:invoice-preservation no-receipt))))
      (is (not= no-credit no-receipt)))))

;; ---------------------------------------------------------------------------
;; GET /api/claim/:id
;; ---------------------------------------------------------------------------

(deftest an-unknown-claim-is-404-and-never-an-empty-200
  (let [r (edge/claim-verdict-core (seeded) allowlist "did:key:zAlice" "c-never")]
    (is (= 404 (:status r)))
    (is (= "no such claim for this caller" (get-in r [:body :error])))))

(deftest a-blank-claim-id-is-400
  (is (= 400 (:status (edge/claim-verdict-core (seeded) allowlist "did:key:zAlice" ""))))
  (is (= 400 (:status (edge/claim-verdict-core (seeded) allowlist "did:key:zAlice" nil)))))

(deftest another-employees-claim-is-indistinguishable-from-one-that-never-existed
  (testing "a 403 here would confirm the claim exists and make a claim id
            something a stranger can probe for"
    (let [st (seeded)
          _ (submit st "did:key:zAlice" (body))
          mine (edge/claim-verdict-core st allowlist "did:key:zAlice" "c-1")
          theirs (edge/claim-verdict-core st allowlist "did:key:zBob" "c-1")
          absent (edge/claim-verdict-core st allowlist "did:key:zBob" "c-never")]
      (is (= 200 (:status mine)))
      (is (= 404 (:status theirs)))
      (is (= theirs absent) "byte-identical, deliberately"))))

(deftest a-claims-history-reads-back-in-order-with-its-verdict
  (let [st (seeded)
        _ (store/register-receipt! st {:receipt-id "rc-gone" :employee-id "emp-1"
                                       :kind :invoice-received :amount 5000
                                       :registration-number "T1234567890123"
                                       :origin :paper :preservation :none})
        _ (submit st "did:key:zAlice"
                  (body :receipt "rc-gone" :tax-treatment :input-tax-credit
                        :preservation-exception {:kind :disaster}))
        r (edge/claim-verdict-core st allowlist "did:key:zAlice" "c-1")]
    (is (= 200 (:status r)))
    (is (= :request-approval (get-in r [:body :disposition])))
    (is (= [:request-approval] (mapv :disposition (get-in r [:body :history]))))
    (is (= :checked (get-in r [:body :tax :invoice-preservation]))
        "the coverage travels with the stored verdict, not recomputed here")))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(deftest the-ledger-route-serves-the-callers-own-slice-only
  (let [st (seeded)
        _ (submit st "did:key:zAlice" (body))
        _ (submit st "did:key:zAlice" (body :claim-id "c-2" :amount 6000))
        _ (submit st "did:key:zBob" (body :claim-id "c-3" :receipt "rc-2"))
        mine (edge/ledger-core st allowlist "did:key:zAlice")]
    (is (= 200 (:status mine)))
    (is (= :caller-only (get-in mine [:body :scope])))
    (is (= 2 (get-in mine [:body :count])))
    (is (= ["c-1" "c-2"] (mapv :claim-id (get-in mine [:body :entries]))))
    (is (= [:commit :hold] (mapv :disposition (get-in mine [:body :entries])))
        "a hold is in the caller's ledger too — an audit trail of only the
         operations that succeeded is an audit trail of the wrong thing")
    (testing "and emp-2's claim is not in it"
      (is (not (contains? (set (mapv :claim-id (get-in mine [:body :entries]))) "c-3"))))))

(deftest an-employee-with-no-entries-gets-a-real-200
  (testing "unlike an unknown claim id, `you have filed nothing` is something
            this actor knows"
    (let [r (edge/ledger-core (seeded) allowlist "did:key:zBob")]
      (is (= 200 (:status r)))
      (is (= 0 (get-in r [:body :count])))
      (is (= [] (get-in r [:body :entries]))))))

;; ---------------------------------------------------------------------------
;; The surface is exactly three routes
;; ---------------------------------------------------------------------------

(deftest store-mode-reads-only-values-it-recognises
  (is (nil? (edge/store-mode {})))
  (is (nil? (edge/store-mode {"KEIHI_STORE" ""})))
  (is (= :ephemeral (edge/store-mode {"KEIHI_STORE" "ephemeral"})))
  (is (= :datomic (edge/store-mode {"KEIHI_STORE" "  datomic  "})))
  (testing "a typo in a deployment variable must not silently select a mode"
    (is (nil? (edge/store-mode {"KEIHI_STORE" "ephemral"})))
    (is (nil? (edge/store-mode {"KEIHI_STORE" "durable"})))))

(deftest an-unconfigured-store-serves-503-and-says-whose-fault-it-is
  (let [direct (edge/store-unconfigured-response)
        routed (edge/route nil nil allowlist "did:key:zAlice"
                           {:method :get :path "/api/ledger"})]
    (is (= 503 (:status direct)))
    (testing "not a 409 :no-employee — an empty in-process store would fail the
              governor's registration check and blame the CALLER"
      (is (= "no store configured" (get-in direct [:body :error])))
      (is (re-find #"ephemeral" (get-in direct [:body :hint]))))
    (is (= direct routed) "the router reaches the same refusal")))

(deftest the-router-serves-three-routes-and-refuses-the-rest
  (let [st (seeded)
        call (fn [method path & [b]]
               (edge/route st :ephemeral allowlist "did:key:zAlice"
                           {:method method :path path :body b}))]
    (is (= 200 (:status (call :post "/api/claim" (body)))))
    (is (= 200 (:status (call :get "/api/claim/c-1"))))
    (is (= 200 (:status (call :get "/api/ledger"))))
    (testing "a path nobody declared is 404"
      (is (= 404 (:status (call :get "/api/payroll"))))
      (is (= 404 (:status (call :get "/")))))
    (testing "a method nobody declared on a real route is 405, not 404"
      (is (= 405 (:status (call :get "/api/claim"))))
      (is (= [:post] (get-in (call :get "/api/claim") [:body :allow])))
      (is (= 405 (:status (call :post "/api/ledger"))))
      (is (= 405 (:status (call :post "/api/claim/c-1")))))))

(deftest the-router-carries-reads-across-requests-on-the-durable-backend
  (testing "a router that built its own store per request would answer 404 to
            every read while looking healthy"
    (let [st (seeded store/datomic-store :jp)
          call (fn [method path & [b]]
                 (edge/route st :datomic allowlist "did:key:zAlice"
                             {:method method :path path :body b}))]
      (is (= 200 (:status (call :post "/api/claim" (body)))))
      (let [r (call :get "/api/claim/c-1")]
        (is (= 200 (:status r)))
        (is (= :commit (get-in r [:body :disposition]))))
      (is (= 1 (get-in (call :get "/api/ledger") [:body :count])))
      (is (= :datomic (get-in (call :post "/api/claim" (body :claim-id "c-2"))
                              [:body :store]))))))

(deftest the-escalating-ops-have-no-http-representation
  (let [publics (set (keys (ns-publics 'keihi.edge.endpoints)))]
    (is (contains? publics 'submit-claim-core!))
    (doseq [absent '[disburse-core! amend-claim-core! reject-claim-core!
                     approve-core!]]
      (is (not (contains? publics absent)) (str absent " must not exist")))))
