(ns airfreightops.store
  "SSoT for the ISIC-5120 'Freight air transport' (cargo airline and air
  freight forwarding GROUND LOGISTICS SCHEDULING coordination) actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates GROUND LOGISTICS SCHEDULING ONLY: cargo/
  manifest/AWB (air waybill) record logging, warehouse/ramp/loading-dock
  ground-operation scheduling coordination, ground-equipment maintenance
  procurement coordination with registered contractors, and weight-and-
  balance/dangerous-goods/airworthiness-concern flagging for human
  triage. It NEVER touches flight operations, piloting, or air-traffic-
  control functions, and NEVER finalizes/authorizes an airworthiness
  clearance, a weight-and-balance sign-off, or a dangerous-goods
  acceptance determination -- see `airfreightops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `facilities` directory keyed by `:facility-id` STRING
  (the ground-handling warehouse/ramp facility's own carrier/warehouse-
  license registration record) and a `contractors` directory keyed by
  `:contractor-id` STRING (never keywords -- consistent keying from the
  start, avoiding the silent-miss bug that has plagued earlier sibling
  actors).

  A registered/verified facility record (the carrier's own operating
  certificate AND the facility's own bonded-warehouse/ground-handling
  license) must exist before ANY proposal targeting that facility may
  ever commit or escalate -- `airfreightops.governor`'s
  `facility-unverified-violations` re-derives this from the facility's
  own `:registered?`/`:verified?` fields, never from proposal self-
  report, checked unconditionally on ALL FOUR ops. A
  `:coordinate-maintenance-order` proposal additionally names a
  registered ground-equipment maintenance contractor via its own
  `:contractor-id`; the SAME 'ground truth, not self-report' discipline
  applies via `contractor-unverified-violations`.

  The ledger stays append-only: which facility a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (facility-record [s facility-id] "Registered ground-handling facility
    record (carrier/warehouse-license), or nil. Facility map:
    {:facility-id .. :name .. :carrier-id .. :registered? bool :verified? bool}.")
  (all-facility-records [s])
  (contractor-record [s contractor-id] "Registered ground-equipment
    maintenance-contractor record, or nil. Contractor map:
    {:contractor-id .. :name .. :registered? bool :verified? bool}.")
  (all-contractor-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facility-records [s facilities] "replace/seed the facility directory (map facility-id->facility)")
  (with-contractor-records [s contractors] "replace/seed the contractor directory (map contractor-id->contractor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility/contractor directory covering both
  the happy path and the governor's own hard checks, so the actor +
  tests run offline."
  []
  {:facilities
   {"facility-1" {:facility-id "facility-1" :name "Northgate Air Cargo Terminal"
                   :carrier-id "carrier-1" :registered? true :verified? true}
    "facility-2" {:facility-id "facility-2" :name "Harborview Ramp Warehouse"
                   :carrier-id "carrier-2" :registered? true :verified? true}
    "facility-3" {:facility-id "facility-3" :name "Pending-License Cargo Shed (in intake)"
                   :carrier-id "carrier-3" :registered? true :verified? false}}
   :contractors
   {"contractor-1" {:contractor-id "contractor-1" :name "Ramptech GSE Maintenance"
                     :registered? true :verified? true}
    "contractor-2" {:contractor-id "contractor-2" :name "Unverified Loader Repair Co."
                     :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility-record [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facility-records [_] (sort-by :facility-id (vals (:facilities @a))))
  (contractor-record [_ contractor-id] (get-in @a [:contractors contractor-id]))
  (all-contractor-records [_] (sort-by :contractor-id (vals (:contractors @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facility-records [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s)
  (with-contractor-records [s contractors] (when (seq contractors) (swap! a assoc :contractors contractors)) s))

(defn seed-db
  "A MemStore seeded with the demo facility/contractor directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `facilities`/`contractors` maps
  (facility-id/contractor-id string -> record map) -- the primary
  test/dev entry point. Either may be empty (an unregistered-everywhere
  facility)."
  ([facilities] (mem-store facilities {}))
  ([facilities contractors]
   (->MemStore (atom {:facilities (or facilities {}) :contractors (or contractors {})
                       :ledger [] :coordination-log []}))))
