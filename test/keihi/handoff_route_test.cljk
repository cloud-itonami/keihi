(ns keihi.handoff-route-test
  "Where the carrier brings back what the ledger actor said.

  `keihi.handoff` was pure and therefore reachable from nothing: the
  namespace existed, the facts existed, and no path produced one. These
  tests are about the ROUTE, for the same reason the posting tests in the
  ledger actor are phrased about its actor rather than its projection."
  (:require [clojure.test :refer [deftest is testing]]
            [keihi.store :as store]
            [keihi.edge.endpoints :as e]))

(def ^:private did "did:key:z6MkAAA")
(def ^:private other "did:key:z6MkBBB")
(def ^:private allow (e/parse-allowlist (str did "=emp-1," other "=emp-2")))

(defn- seeded [] (store/mem-store))

(defn- pairs [& ps]
  (pr-str {:handoffs (vec (for [[claim status body] ps]
                            {:claim claim :response {:status status :body body}}))}))

(defn- post! [st raw] (e/record-handoff-core! st allow did raw))

;; ---------------------------------------------------------------------------
;; the route exists and writes
;; ---------------------------------------------------------------------------

(deftest the-carrier-can-bring-an-outcome-back
  (testing "the pure namespace was reachable from nothing until this route"
    (let [st (seeded)
          r (post! st (pairs [{:claim-id "c-1"} 200 {:ok true :posting "je-1" :duplicate? false}]))]
      (is (= 200 (:status r)))
      (is (= 1 (:recorded (:body r))))
      (is (= {:posted 1} (:summary (:body r))))
      (testing "and it is in the ledger, not only in the response"
        (let [facts (filter #(= :handoff (:disposition %)) (store/ledger st))]
          (is (= 1 (count facts)))
          (is (= :posted (:handoff/outcome (first facts))))
          (is (= "je-1" (:handoff/posting (first facts)))))))))

(deftest the-good-outcome-is-written-too
  (testing "a ledger recording only refusals could not answer
            `was this claim posted?`"
    (let [st (seeded)]
      (post! st (pairs [{:claim-id "a"} 200 {:ok true :posting "je-a" :duplicate? false}]
                       [{:claim-id "b"} 200 {:ok true :posting "je-b" :duplicate? true}]))
      (is (= 2 (count (filter #(= :handoff (:disposition %)) (store/ledger st))))))))

(deftest the-response-says-what-still-needs-somebody
  (let [r (post! (seeded)
                 (pairs [{:claim-id "a"} 200 {:ok true :posting "je-a" :duplicate? false}]
                        [{:claim-id "b"} 200 {:ok true :posting "je-b" :duplicate? true}]
                        [{:claim-id "c"} 409 {:violations [{:rule :unbalanced-entry :detail "x"}]}]
                        [{:claim-id "d"} 403 {:error "nope"}]))]
    (is (= {:posted 1 :duplicate 1 :held 1 :rejected 1} (:summary (:body r))))
    (is (= [{:claim-id "c"} {:claim-id "d"}]
           (mapv :handoff/claim (:unresolved (:body r))))
        "a duplicate needs nobody — it is confirmation, not a problem")))

;; ---------------------------------------------------------------------------
;; explicit pairs cannot misattribute
;; ---------------------------------------------------------------------------

(deftest each-outcome-names-its-own-claim
  (testing "the batch form has to refuse a length mismatch because results
            are positional. Here nothing is lined up, so that failure cannot
            arise at all — where a carrier can send pairs, pairs are
            strictly better than order."
    (let [st (seeded)
          _ (post! st (pairs [{:claim-id "x"} 409 {:violations [{:rule :r :detail "d"}]}]
                             [{:claim-id "y"} 200 {:ok true :posting "je-y" :duplicate? false}]))
          facts (filter #(= :handoff (:disposition %)) (store/ledger st))]
      (is (= [{:claim-id "x"} {:claim-id "y"}] (mapv :handoff/claim facts)))
      (is (= [:held :posted] (mapv :handoff/outcome facts))))))

(deftest an-unrecognised-response-is-written-as-unknown-not-posted
  (let [st (seeded)]
    (post! st (pairs [{:claim-id "z"} 418 {:teapot true}]))
    (let [f (first (filter #(= :handoff (:disposition %)) (store/ledger st)))]
      (is (= :unknown-response (:handoff/outcome f)))
      (is (some? (:handoff/response f)) "and keeps enough to diagnose it"))))

;; ---------------------------------------------------------------------------
;; refusals
;; ---------------------------------------------------------------------------

(deftest a-malformed-body-writes-nothing
  (testing "half-recording a batch would leave a ledger nobody can trust more
            than an empty one"
    (doseq [raw ["not-edn" (pr-str {}) (pr-str {:handoffs []})
                 (pr-str {:handoffs [{:claim {:claim-id "a"}}]})
                 (pr-str {:handoffs [{:response {:status 200}}]})
                 (pr-str {:handoffs [{:claim {:claim-id "a"} :response "nope"}]})]]
      (let [st (seeded)
            r (post! st raw)]
        (is (= 400 (:status r)) (str "should refuse " (subs raw 0 (min 30 (count raw)))))
        (is (empty? (store/ledger st)) "and write nothing")))))

(deftest an-oversized-body-is-refused-with-the-limit
  (let [big (pr-str {:handoffs (vec (repeat (inc e/max-handoffs)
                                            {:claim {:claim-id "a"}
                                             :response {:status 200 :body {}}}))})
        st (seeded)
        r (post! st big)]
    (is (= 400 (:status r)))
    (is (= e/max-handoffs (:max (:body r))))
    (is (empty? (store/ledger st)))))

(deftest the-route-has-the-same-two-gates
  (is (= 503 (:status (e/record-handoff-core! (seeded) nil did "{}"))))
  (is (= 403 (:status (e/record-handoff-core! (seeded) allow "did:key:zZZZ" "{}")))))

;; ---------------------------------------------------------------------------
;; the dispatcher
;; ---------------------------------------------------------------------------

(deftest the-router-knows-the-route
  (let [st (seeded)
        body (pairs [{:claim-id "a"} 200 {:ok true :posting "je-a" :duplicate? false}])]
    (is (= 200 (:status (e/route st :ephemeral allow did
                                 {:method :post :path "/api/handoff" :body body}))))
    (testing "and the wrong verb is 405, not 404 — a caller using GET on a
              real route is a different mistake from inventing one"
      (let [r (e/route st :ephemeral allow did {:method :get :path "/api/handoff"})]
        (is (= 405 (:status r)))
        (is (= [:post] (get-in r [:body :allow])))))))
