(ns keihi.advisor
  "KeihiAdvisor — proposes what to do with an expense claim (submit it, amend
  it, disburse the reimbursement, reject it) for a registered employee.

  The advisor is swappable: `mock-advisor` (deterministic; the default in
  dev / tests / CI) or `llm-advisor` (wraps a real `langchain.model/ChatModel`).
  Either way the advisor ONLY produces a PROPOSAL. It never writes to the
  store, and it has no notion of employee provenance, receipt ownership, claim
  arithmetic or 消費税法 — `keihi.governor` is the independent system that
  decides whether the proposal may proceed.

  A proposal is a map:

    {:op :submit-claim | :amend-claim | :disburse | :reject-claim
     :effect :propose         ; the advisor NEVER emits a raw store write
     :receipt str-or-nil      ; the cited 領収書/請求書 id
     :amount n                ; the total being claimed
     :lines [{:purpose str :amount n} ...]
     :tax-treatment :input-tax-credit | nil
     :preservation-exception {...} | nil   ; 消費税法 第三十条第七項 ただし書
     :stake :low | :medium | :high
     :confidence 0.0-1.0
     :rationale str}

  LLM parse failures always yield `:confidence 0.0` — never a fabricated
  confidence — which forces the governor to escalate or hold. Note that the
  governor treats an ABSENT `:confidence` as 0.0 for the same reason: a
  proposal that has not said how confident it is has not said it is confident."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared fields straight
  through — a stand-in for what an LLM would extract from a photographed
  receipt — with a stake-derived confidence.

  It deliberately does NOT recompute `:amount` from `:lines`. Making the mock
  self-consistent would mean the governor's arithmetic check could never fire
  in an end-to-end test, and a check that cannot fire is not a check."
  [_store {:keys [op stake receipt amount lines tax-treatment
                  preservation-exception] :as request}]
  {:op op
   :effect :propose
   :receipt receipt
   :amount amount
   :lines (vec lines)
   :tax-treatment tax-treatment
   :preservation-exception preservation-exception
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for employee "
                   (:employee-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an expense-reimbursement advisor. Given a claim request, propose an
   :op, the cited :receipt, the :amount, itemised :lines, whether 仕入税額控除
   is being claimed (:tax-treatment), an honest :confidence (0.0-1.0) and a
   :stake (:low/:medium/:high). Never fabricate a receipt, an amount, a
   registration number or a confidence you do not have.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this namespace
  has no hard dependency beyond the protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "expense claim request: "
                                             (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
