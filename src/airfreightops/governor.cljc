(ns airfreightops.governor
  "AirCargoGroundOpsGovernor -- the independent compliance layer that
  earns the AirFreightAdvisor the right to commit. The advisor has no
  notion of whether a ground-handling facility is actually registered
  and carrier/warehouse-license-verified, whether a named maintenance-
  order contractor is itself a registered/verified counterparty,
  whether its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- GROUND LOGISTICS
  SCHEDULING ONLY (cargo/manifest/AWB record logging, warehouse/ramp/
  loading-dock ground-operation scheduling coordination, ground-
  equipment maintenance procurement coordination, weight-and-balance/
  dangerous-goods/airworthiness-concern flagging). It NEVER performs or
  authorizes:
    - any flight-operations, piloting, or air-traffic-control function,
      in any way
    - directly finalizing an airworthiness clearance, a weight-and-
      balance sign-off, or a dangerous-goods (IATA DGR) acceptance
      determination
    - authorizing a flight to depart, or overriding a pilot's or flight
      crew's judgment

  Air freight transport carries the strictest safety regime in this
  batch -- aircraft airworthiness, cargo weight-and-balance, and
  hazmat/dangerous-goods (IATA DGR) restrictions are all directly
  life-safety-critical. Every check below that touches this territory is
  a HARD, PERMANENT, un-overridable block -- never merely a rollout
  milestone, and never something a human approval can waive.

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Facility unverified         -- the target ground-handling
                                       facility's own carrier/warehouse-
                                       license registration record must
                                       exist AND be independently
                                       confirmed `:registered?`/
                                       `:verified?` in the store before
                                       ANY proposal for it may commit or
                                       even escalate. Never trusts a
                                       proposal's own claim about the
                                       facility -- re-derived from the
                                       facility's own record, the same
                                       'ground truth, not self-report'
                                       discipline every sibling actor's
                                       governor uses. Checked
                                       UNCONDITIONALLY on all four ops.
    2. Contractor unverified       -- for `:coordinate-maintenance-
                                       order` ONLY, the proposal's own
                                       drafted `:value` must name a
                                       `:contractor-id` that resolves to
                                       an independently
                                       `:registered?`/`:verified?`
                                       ground-equipment maintenance-
                                       contractor record. A missing
                                       contractor-id, or one that
                                       resolves to an unregistered or
                                       unverified contractor, is a HARD
                                       block.
    3. Effect not :propose         -- every proposal's `:effect` MUST be
                                       `:propose`. Any other effect value
                                       is, by construction, a claim to
                                       directly actuate/commit outside
                                       governance -- HARD block, not
                                       merely low-confidence.
    4. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op, summary, rationale,
                                       cites or draft value touches
                                       directly finalizing an
                                       airworthiness clearance,
                                       finalizing/signing off a weight-
                                       and-balance determination,
                                       finalizing a dangerous-goods
                                       acceptance determination,
                                       authorizing a flight to depart, or
                                       any other flight-operations/
                                       piloting/air-traffic-control
                                       action is a HARD, PERMANENT block
                                       -- this actor's charter excludes
                                       that territory structurally, not
                                       as a rollout milestone. Evaluated
                                       UNCONDITIONALLY on every proposal.
                                       An op outside the closed four-op
                                       allowlist is the SAME failure mode
                                       (an advisor proposing something it
                                       was never authorized to propose)
                                       and is folded into this same
                                       check. `:flag-safety-concern`
                                       itself is never excluded by this
                                       check -- surfacing a weight-and-
                                       balance/dangerous-goods/
                                       airworthiness concern for a human
                                       is exactly this actor's job; only
                                       FINALIZING/signing-off/accepting/
                                       authorizing that concern (issuing
                                       an airworthiness clearance,
                                       signing off weight-and-balance,
                                       accepting a dangerous-goods
                                       shipment, authorizing departure)
                                       is excluded (see
                                       `scope-excluded-terms` below --
                                       phrased as the finalization/
                                       execution ACTION, never a bare
                                       noun like 'airworthiness',
                                       'weight and balance' or
                                       'dangerous goods', so the default
                                       mock advisor's own
                                       `:flag-safety-concern` rationale
                                       never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `airfreightops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one. This is safety-critical, not
      merely a business-process convenience -- a 'flag a concern' op
      must always escalate immediately and never auto-commit.
    - A `:coordinate-maintenance-order` whose drafted `:value` names an
      `:estimated-cost` above `maintenance-cost-threshold` -- a
      large-value ground-equipment maintenance procurement proposal
      always needs a human sign-off, even when the governor and phase
      would otherwise allow auto-commit."
  (:require [kotoba.lang.text :as str]
            [airfreightops.store :as store]))

(def confidence-floor 0.6)

(def maintenance-cost-threshold
  "Example single-facility ground-equipment maintenance procurement
  threshold (USD-equivalent units, domain-illustrative -- not a
  universal cross-domain constant). A `:coordinate-maintenance-order`
  proposal citing an `:estimated-cost` above this value ALWAYS escalates
  to human sign-off, regardless of confidence or rollout phase."
  5000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). NONE of
  these ops touch flight operations, piloting or air-traffic control, and
  NONE finalize an airworthiness clearance, a weight-and-balance sign-
  off, or a dangerous-goods acceptance determination -- this actor
  coordinates ground logistics scheduling only."
  #{:log-shipment-record :schedule-ground-operation
    :coordinate-maintenance-order :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not. `:flag-safety-
  concern` must escalate IMMEDIATELY -- safety-critical, never merely a
  business-process convenience -- and is never a member of any phase's
  `:auto` set (see `airfreightops.phase`)."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing an
  airworthiness clearance, finalizing/signing off a weight-and-balance
  determination, finalizing a dangerous-goods (IATA DGR) acceptance
  determination, authorizing a flight to depart, or any other flight-
  operations/piloting/air-traffic-control action, rather than merely
  coordinating ground logistics scheduling around it. Scanned across the
  proposal's op/summary/rationale/cites/value, never trusting the
  advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'finalize the airworthiness clearance', 'sign off on the
  weight and balance', 'accept the dangerous goods shipment', 'authorize
  the flight to depart'), never a bare noun like 'airworthiness', 'weight
  and balance' or 'dangerous goods' -- a bare noun would accidentally
  match inside this actor's own legitimate `:flag-safety-concern` default
  proposal text (whose whole job is to talk about weight-and-balance/
  dangerous-goods/airworthiness concerns) and self-block the happy path.
  See
  `airfreightops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the airworthiness clearance" "finalized the airworthiness clearance" "finalizing the airworthiness clearance"
   "issue the airworthiness clearance" "issued the airworthiness clearance" "issuing the airworthiness clearance"
   "certify the aircraft as airworthy" "certified the aircraft as airworthy" "certifying the aircraft as airworthy"
   "sign off on the weight and balance" "signed off on the weight and balance" "signing off on the weight and balance"
   "finalize the weight and balance" "finalized the weight and balance" "finalizing the weight and balance"
   "approve the weight and balance sheet" "approved the weight and balance sheet" "approving the weight and balance sheet"
   "accept the dangerous goods shipment" "accepted the dangerous goods shipment" "accepting the dangerous goods shipment"
   "finalize the dangerous goods acceptance" "finalized the dangerous goods acceptance" "finalizing the dangerous goods acceptance"
   "clear the dangerous goods for loading" "cleared the dangerous goods for loading" "clearing the dangerous goods for loading"
   "authorize the flight to depart" "authorized the flight to depart" "authorizing the flight to depart"
   "clear the aircraft for departure" "cleared the aircraft for departure" "clearing the aircraft for departure"
   "override the pilot's judgment" "overrode the pilot's judgment" "overriding the pilot's judgment"
   "override the flight crew's judgment" "overrode the flight crew's judgment" "overriding the flight crew's judgment"
   "take direct control of the aircraft" "took direct control of the aircraft" "taking direct control of the aircraft"
   "bypass the air traffic control instruction" "bypassed the air traffic control instruction" "bypassing the air traffic control instruction"
   "issue a piloting instruction" "issued a piloting instruction" "issuing a piloting instruction"
   "耐空性証明を確定" "耐空性証明を発行した" "耐空性証明を発行する"
   "重量重心配分を確定承認" "重量重心配分を確定した" "ウェイトアンドバランスを確定承認"
   "危険物受託を確定" "危険物搭載を承認した" "危険物受託を確定した"
   "航空機の出発を許可した" "出発を許可する"
   "操縦士の判断を覆した" "運航乗務員の判断を覆した"
   "航空管制指示を回避した" "管制指示を迂回した"])

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target ground-handling facility's own carrier/warehouse-license
  registration record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:facility-id` claim without a store lookup. Checked
  UNCONDITIONALLY on all four ops."
  [{:keys [facility-id]} st]
  (let [f (store/facility-record st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証の施設/キャリア倉庫免許 -- いかなる提案も進められない")}])))

(defn- contractor-unverified-violations
  "For `:coordinate-maintenance-order` ONLY, the proposal's own drafted
  `:value` must name a `:contractor-id` that resolves to an independently
  `:registered?`/`:verified?` ground-equipment maintenance-contractor
  record. A missing contractor-id, or one that resolves to an
  unregistered/unverified contractor, is a HARD block -- never trust the
  proposal's own contractor claim without a store lookup, the SAME
  'ground truth, not self-report' discipline as
  `facility-unverified-violations`, reapplied to the ground-equipment
  maintenance-supply-chain counterparty."
  [proposal st]
  (when (= :coordinate-maintenance-order (:op proposal))
    (let [contractor-id (get-in proposal [:value :contractor-id])
          c (and contractor-id (store/contractor-record st contractor-id))]
      (when-not (and c (:registered? c) (:verified? c))
        [{:rule :contractor-unverified
          :detail (str (or contractor-id "(contractor-id missing)")
                        " は未登録または未検証の地上支援機材(GSE)整備業者 -- 整備発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing an airworthiness
  clearance, finalizing/signing off a weight-and-balance determination,
  finalizing a dangerous-goods acceptance determination, authorizing a
  flight to depart, or any other flight-operations/piloting/air-traffic-
  control action, regardless of confidence or how clean every other
  check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "耐空性証明の確定、重量重心配分(ウェイト&バランス)の確定承認、危険物(IATA DGR)受託の確定、飛行運航/操縦/航空管制に関わる行為は永久に禁止"}])))

(defn- high-cost-maintenance-order?
  "A `:coordinate-maintenance-order` proposal citing an `:estimated-cost`
  above `maintenance-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-maintenance-order (:op proposal))
       (some-> proposal :value :estimated-cost (> maintenance-cost-threshold))))

(defn check
  "Censors an AirFreightAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (contractor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-maintenance-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :facility-id (:facility-id request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
