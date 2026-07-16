(ns airfreightops.advisor
  "AirFreightAdvisor -- the *contained intelligence node* for the
  ISIC-5120 'Freight air transport' (cargo airline and air freight
  forwarding) GROUND LOGISTICS SCHEDULING coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: cargo/manifest/AWB (air waybill) record logging, warehouse/
  ramp/loading-dock ground-operation scheduling coordination, ground-
  equipment maintenance procurement coordination, and weight-and-
  balance/dangerous-goods/airworthiness-concern flagging. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER a
  direct actuation -- every proposal's `:effect` is always `:propose`.
  Every output is censored downstream by `airfreightops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts a direct flight-operations/piloting/air-
  traffic-control action, an airworthiness-clearance finalization, a
  weight-and-balance sign-off, a dangerous-goods acceptance
  determination, or any action authorizing a flight to depart -- those
  are permanently out of scope for this actor, not merely un-
  implemented. `airfreightops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode (a
  compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :facility-id str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-shipment-record
  "Draft a cargo/manifest/AWB (air waybill) record log entry. Pure
  logging of observed shipment data (AWB numbers, manifest lines, piece
  count, gross weight, cool-chain temperature readings) -- never a
  weight-and-balance or airworthiness decision."
  [_db {:keys [facility-id patch]}]
  {:op          :log-shipment-record
   :facility-id facility-id
   :summary     (str facility-id " の貨物/マニフェスト/AWB記録を記録: " (pr-str (keys patch)))
   :rationale   "貨物マニフェスト・AWB(航空貨物運送状)・積付状況の観察記録のみ。重量重心配分や耐空性の判断は含まない。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.93})

(defn- propose-ground-operation
  "Draft a warehouse/ramp/loading-dock ground-operation scheduling
  proposal (a dock-window/ramp-slot entry, never a flight-operations or
  dispatch action)."
  [_db {:keys [facility-id patch]}]
  {:op          :schedule-ground-operation
   :facility-id facility-id
   :summary     (str facility-id " の倉庫/ランプ/積卸バーススケジュールを提案: " (pr-str (keys patch)))
   :rationale   "倉庫/ランプ/積卸バース割当・地上作業スケジュール調整提案のみ。飛行運航の最終判断は運航乗務員/航空管制が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.88})

(defn- propose-maintenance-order
  "Draft a ground-equipment (GSE -- loaders, dollies, ULD handling gear)
  maintenance procurement coordination request naming a registered
  contractor -- never a finalized purchase order; a human always
  confirms procurement."
  [_db {:keys [facility-id patch]}]
  {:op          :coordinate-maintenance-order
   :facility-id facility-id
   :summary     (str facility-id " 向け地上支援機材(GSE)整備の発注調整を提案: " (pr-str (keys patch)))
   :rationale   "地上支援機材(GSE)整備・点検等の仕入先発注調整提案のみ。確定発注は人間が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.90})

(defn- propose-safety-concern
  "Surface an observed weight-and-balance/dangerous-goods (IATA DGR)/
  airworthiness concern (a manifest weight discrepancy, a dangerous-
  goods declaration/placarding anomaly, a suspected structural/loading
  irregularity) for HUMAN triage. This op ALWAYS escalates in
  `airfreightops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  Deliberately reports the OBSERVATION only, never a finalization/
  clearance/sign-off/acceptance action, so the default rationale never
  trips the governor's `scope-excluded-terms` (see that var's
  docstring)."
  [_db {:keys [facility-id patch]}]
  {:op          :flag-safety-concern
   :facility-id facility-id
   :summary     (str facility-id " の安全性懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale   "重量重心配分・危険物(IATA DGR)申告・耐空性等に関する懸念の観察事実の報告。常に人間(運航乗務員/貨物管理責任者)の確認・対応が必要。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-shipment-record (propose-shipment-record _db request)
                   :schedule-ground-operation (propose-ground-operation _db request)
                   :coordinate-maintenance-order (propose-maintenance-order _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually authorized the flight to depart and overrode the pilot's judgment on the loading discrepancy")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t           :advisor-proposal
   :op          (:op proposal)
   :facility-id (:facility-id proposal)
   :summary     (:summary proposal)
   :confidence  (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
