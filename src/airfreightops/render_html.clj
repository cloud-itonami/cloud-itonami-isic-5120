(ns airfreightops.render-html
  "Build-time HTML renderer for the ISIC-5120 ground-logistics operator
  console.

  This is NOT a mock-up. It drives the REAL actor stack -- the real
  `airfreightops.store/seed-db`, the real `airfreightops.operation/build`
  langgraph StateGraph, the real `airfreightops.governor` and the real
  `airfreightops.phase` gate -- and then renders whatever the store
  actually ended up holding. Every facility id, contractor id, ledger
  fact, hold rule and hold detail on the page is read back out of the
  store after the run; nothing on the page is hand-written domain data.

  Two invariants this renderer deliberately respects:

    1. INPUT PROVENANCE. Every `:facility-id` / `:contractor-id` driven
       below comes from `airfreightops.store/demo-data` -- this actor has
       no intake/registration op in `governor/allowed-ops`, so a subject
       that is not in the seed could not have been created here. The
       `:facility-unverified` HARD hold is therefore driven with
       `facility-3` (a REAL seeded record that is `:registered? true`
       but `:verified? false`), not with an invented id.

    2. ONLY REACHABLE FACT TYPES ARE RENDERED. `airfreightops.operation`
       emits `:advisor-proposal`, `:approval-requested` and
       `:approval-granted` to the in-memory `:audit` channel ONLY -- the
       `:commit` node appends `:committed` and the `:hold` node appends
       `:governor-hold` / `:approval-rejected`, and those three are the
       ONLY fact types that ever reach `store/append-ledger!`. The status
       function below branches on exactly those three and nothing else.

  Deterministic: seeded store, deterministic mock advisor, no clock, no
  randomness, key-sorted payload rendering. Re-running produces a
  byte-identical file."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [airfreightops.advisor :as advisor]
            [airfreightops.governor :as governor]
            [airfreightops.operation :as op]
            [airfreightops.phase :as phase]
            [airfreightops.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private coordinator-phase-1
  {:actor-id "coord-1" :actor-role :ground-logistics-coordinator :phase 1})

(def ^:private coordinator-phase-3
  {:actor-id "coord-1" :actor-role :ground-logistics-coordinator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(defn- approve! [actor tid]
  (resume! actor tid {:status :approved :by "ground-logistics-coordinator-1"}))

(defn- reject! [actor tid]
  (resume! actor tid {:status :rejected :by "cargo-safety-officer-1"}))

(defn run-demo!
  "Drives the real OperationActor graph over the real seeded store and
  returns the store. Scenario ids are all seed subjects:
  facility-1 / facility-2 / facility-3, contractor-1 / contractor-2."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        ;; A rigged advisor that claims a DIRECT actuation instead of a
        ;; proposal -- the only way to exercise :effect-not-propose, and
        ;; the same seam `airfreightops.sim` uses. Shares the same store.
        actor-direct (op/build db {:advisor (reify advisor/Advisor
                                              (-advise [_ _ req]
                                                (assoc (advisor/infer nil req) :effect :commit)))})]

    ;; --- approval path: phase 1 enables logging but never auto-commit ---
    (exec! actor "t1" {:op :log-shipment-record :facility-id "facility-1"
                       :patch {:awb-number "020-12345675" :piece-count 42
                               :gross-weight-kg 3200 :dg-class "none"}}
           coordinator-phase-1)
    (approve! actor "t1")

    ;; --- clean auto-commits at phase 3 ---
    (exec! actor "t2" {:op :log-shipment-record :facility-id "facility-2"
                       :patch {:awb-number "020-12345699" :piece-count 30
                               :gross-weight-kg 1800 :dg-class "none"}}
           coordinator-phase-3)

    (exec! actor "t3" {:op :schedule-ground-operation :facility-id "facility-1"
                       :patch {:dock "dock-4" :eta "2026-07-20T06:00:00Z"
                               :etd "2026-07-20T18:00:00Z"}}
           coordinator-phase-3)

    (exec! actor "t4" {:op :coordinate-maintenance-order :facility-id "facility-1"
                       :patch {:item "routine loader inspection"
                               :estimated-cost 1200.0 :contractor-id "contractor-1"}}
           coordinator-phase-3)

    ;; --- high-cost maintenance order: over the governor's own threshold ---
    (exec! actor "t5" {:op :coordinate-maintenance-order :facility-id "facility-2"
                       :patch {:item "main ULD loader overhaul"
                               :estimated-cost 42000.0 :contractor-id "contractor-1"}}
           coordinator-phase-3)
    (approve! actor "t5")

    ;; --- safety concern: always escalates, at every phase ---
    (exec! actor "t6" {:op :flag-safety-concern :facility-id "facility-2"
                       :patch {:concern "dangerous-goods declaration mismatch on AWB 020-12345699"
                               :confidence 0.92}}
           coordinator-phase-3)
    (approve! actor "t6")

    ;; --- safety concern the human REJECTS -> :approval-rejected in the ledger ---
    (exec! actor "t7" {:op :flag-safety-concern :facility-id "facility-1"
                       :patch {:concern "suspected ULD tie-down irregularity reported by ramp crew"
                               :confidence 0.71}}
           coordinator-phase-3)
    (reject! actor "t7")

    ;; --- HARD hold 1: facility-3 is seeded :registered? true, :verified? false ---
    (exec! actor "t8" {:op :log-shipment-record :facility-id "facility-3"
                       :patch {:awb-number "020-12345710" :piece-count 12}}
           coordinator-phase-3)

    ;; --- HARD hold 2: contractor-2 is seeded :registered? true, :verified? false ---
    (exec! actor "t9" {:op :coordinate-maintenance-order :facility-id "facility-1"
                       :patch {:item "conveyor belt survey" :estimated-cost 3000.0
                               :contractor-id "contractor-2"}}
           coordinator-phase-3)

    ;; --- HARD hold 3: advisor claims a direct actuation ---
    (exec! actor-direct "t10" {:op :schedule-ground-operation :facility-id "facility-1"
                               :patch {:dock "dock-2" :eta "2026-07-22T08:00:00Z"}}
           coordinator-phase-3)

    ;; --- HARD hold 4: advisor drifts into permanently excluded scope ---
    (exec! actor "t11" {:op :log-shipment-record :facility-id "facility-1"
                        :out-of-scope? true
                        :patch {:awb-number "020-12345721"}}
           coordinator-phase-3)

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yn [b]
  (if b
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- payload-str
  "Key-sorted rendering of a committed payload -- deterministic
  regardless of map implementation."
  [m]
  (->> (dissoc m :facility-id)
       (sort-by (comp str key))
       (map (fn [[k v]] (str (kw k) "=" (pr-str v))))
       (str/join ", ")))

(defn- facility-facts [ledger facility-id]
  (filter #(= facility-id (:facility-id %)) ledger))

(defn- status-cell
  "Status derived from the LAST fact the store actually holds for this
  facility. Only :committed / :governor-hold / :approval-rejected can
  ever appear here -- see the ns docstring."
  [ledger facility-id]
  (let [f (last (facility-facts ledger facility-id))]
    (case (:t f)
      :committed          (str "<span class=\"ok\">committed</span> <code>"
                               (esc (kw (:op f))) "</code>")
      :governor-hold      (str "<span class=\"critical\">HARD hold: "
                               (esc (str/join ", " (map kw (:basis f)))) "</span>")
      :approval-rejected  "<span class=\"warn\">approval rejected by human</span>"
      "<span class=\"muted\">no ledger activity</span>")))

(defn- facility-row [ledger f]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (:facility-id f)) (esc (:name f)) (esc (:carrier-id f))
          (yn (:registered? f)) (yn (:verified? f))
          (status-cell ledger (:facility-id f))))

(defn- contractor-row [c]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (:contractor-id c)) (esc (:name c))
          (yn (:registered? c)) (yn (:verified? c))))

(defn- gate-cell
  "Derived from the real `governor`/`phase` vars, not restated by hand."
  [op]
  (let [auto3 (:auto (get phase/phases 3))]
    (cond
      (contains? governor/always-escalate-ops op)
      "<span class=\"warn\">ALWAYS escalates to a human -- never auto-commits at any phase</span>"

      (contains? auto3 op)
      (str "<span class=\"ok\">auto-commits at phase 3</span> when governor-clean and confidence &ge; "
           governor/confidence-floor)

      :else
      "<span class=\"warn\">human approval required</span>")))

(defn- extra-gate-cell [op]
  (if (= :coordinate-maintenance-order op)
    (str "<code>:contractor-id</code> must resolve to a registered + verified contractor record; "
         "<code>:estimated-cost</code> &gt; " governor/maintenance-cost-threshold " always escalates")
    "&mdash;"))

(defn- op-row [op]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (str op)) (gate-cell op) (extra-gate-cell op)))

(defn- ops-set-str [s]
  (if (seq s)
    (->> s (map str) sort (map #(str "<code>" (esc %) "</code>")) (str/join " "))
    "<span class=\"muted\">none</span>"))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (format "        <tr><td>%s%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          n (if (= n phase/default-phase) " <span class=\"muted\">(default)</span>" "")
          (esc label) (ops-set-str writes) (ops-set-str auto)))

(defn- hold-rows
  "One row per HARD hold the governor actually produced during the run,
  with the governor's own `:rule` and `:detail` strings."
  [ledger]
  (->> ledger
       (filter #(= :governor-hold (:t %)))
       (mapcat (fn [f]
                 (map (fn [v]
                        (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
                                (esc (kw (:rule v))) (esc (kw (:op f)))
                                (esc (:facility-id f)) (esc (:detail v))))
                      (:violations f))))))

(defn- ledger-row [{:keys [t op facility-id disposition basis summary violations]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw t)) (esc (kw (or op :n-a))) (esc facility-id)
          (esc (kw (or disposition "")))
          (cond
            (seq violations) (str "<span class=\"critical\">"
                                  (esc (str/join ", " (map (comp kw :rule) violations)))
                                  "</span>")
            summary          (esc summary)
            (seq basis)      (esc (str/join ", " (map kw basis)))
            :else            "")))

(defn- coordination-row [{:keys [op facility-id payload]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (kw op)) (esc facility-id) (esc (payload-str (or payload {})))))

(defn render [db]
  (let [ledger      (vec (store/ledger db))
        facilities  (store/all-facility-records db)
        contractors (store/all-contractor-records db)
        coord-log   (vec (store/coordination-log db))
        holds       (filterv #(= :governor-hold (:t %)) ledger)
        committed   (filterv #(= :committed (:t %)) ledger)
        rejected    (filterv #(= :approval-rejected (:t %)) ledger)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>cloud-itonami-isic-5120 &mdash; airfreightops operator console</title>"
     "<style>"
     "body{font:14px/1.55 system-ui,-apple-system,'Hiragino Sans','Noto Sans JP',sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#12263a;color:#fff;padding:1.2rem 2rem}"
     ".bar h1{margin:0;font-size:1.15rem}.bar p{margin:.35rem 0 0;font-size:.82rem;opacity:.78}"
     "main{max-width:1040px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".card h2{margin:0 0 .6rem;font-size:1rem}"
     ".muted{color:#777;font-size:.82rem}"
     "table{border-collapse:collapse;width:100%;font-size:.85rem}"
     "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee;vertical-align:top}"
     "th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}"
     "code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
     ".tiles{display:flex;flex-wrap:wrap;gap:.8rem;margin:0}"
     ".tile{flex:1 1 130px;background:#fafafa;border:1px solid #ececec;border-radius:6px;padding:.7rem .9rem}"
     ".tile b{display:block;font-size:1.5rem;line-height:1.1}"
     "</style></head><body>"

     "<header class=\"bar\"><h1>Freight air transport &mdash; ground logistics scheduling (ISIC 5120) &middot; <code>airfreightops</code></h1>"
     "<p>Generated by <code>airfreightops.render-html</code> from a real "
     "<code>airfreightops.operation</code> langgraph run over <code>airfreightops.store/seed-db</code>. "
     "Ground logistics scheduling only &mdash; never flight operations, piloting or air-traffic control.</p></header><main>"

     "<section class=\"card\"><h2>Run summary</h2><div class=\"tiles\">"
     "<div class=\"tile\"><b>" (count ledger) "</b><span class=\"muted\">ledger facts</span></div>"
     "<div class=\"tile\"><b class=\"ok\">" (count committed) "</b><span class=\"muted\">committed</span></div>"
     "<div class=\"tile\"><b class=\"critical\">" (count holds) "</b><span class=\"muted\">governor HARD holds</span></div>"
     "<div class=\"tile\"><b class=\"warn\">" (count rejected) "</b><span class=\"muted\">approvals rejected</span></div>"
     "<div class=\"tile\"><b>" (count coord-log) "</b><span class=\"muted\">coordination records</span></div>"
     "</div><p class=\"muted\">Only <code>:committed</code>, <code>:governor-hold</code> and "
     "<code>:approval-rejected</code> ever reach the store ledger &mdash; "
     "<code>:advisor-proposal</code>, <code>:approval-requested</code> and <code>:approval-granted</code> "
     "stay in the graph's in-memory <code>:audit</code> channel and are deliberately not rendered as store state.</p></section>"

     "<section class=\"card\"><h2>Ground-handling facility directory</h2>"
     "<p class=\"muted\">Registration/verification is read from each facility's own record &mdash; never from a proposal's self-report.</p>"
     "<table><thead><tr><th>Facility</th><th>Name</th><th>Carrier</th><th>Registered</th><th>Verified</th><th>Last ledger state</th></tr></thead><tbody>\n"
     (str/join "\n" (map #(facility-row ledger %) facilities))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>GSE maintenance contractor directory</h2>"
     "<p class=\"muted\">A <code>:coordinate-maintenance-order</code> must name a contractor that is independently registered and verified here.</p>"
     "<table><thead><tr><th>Contractor</th><th>Name</th><th>Registered</th><th>Verified</th></tr></thead><tbody>\n"
     (str/join "\n" (map contractor-row contractors))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Action gate</h2>"
     "<p class=\"muted\">Derived from <code>governor/allowed-ops</code>, <code>governor/always-escalate-ops</code>, "
     "<code>governor/confidence-floor</code>, <code>governor/maintenance-cost-threshold</code> and <code>phase/phases</code>.</p>"
     "<table><thead><tr><th>Op</th><th>Gate</th><th>Additional hard/soft conditions</th></tr></thead><tbody>\n"
     (str/join "\n" (map op-row (sort-by str governor/allowed-ops)))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Rollout phases</h2>"
     "<table><thead><tr><th>Phase</th><th>Label</th><th>May write</th><th>May auto-commit</th></tr></thead><tbody>\n"
     (str/join "\n" (map phase-row (sort-by key phase/phases)))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Governor HARD holds produced by this run</h2>"
     "<p class=\"muted\">Every row below is a real <code>:governor-hold</code> fact appended to the store by the "
     "<code>:hold</code> node &mdash; rule name and detail are the governor's own strings. HARD holds are permanent and "
     "cannot be waived by any human approval.</p>"
     "<table><thead><tr><th>Rule</th><th>Op</th><th>Facility</th><th>Governor detail</th></tr></thead><tbody>\n"
     (str/join "\n" (hold-rows ledger))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Committed coordination log</h2>"
     "<table><thead><tr><th>Op</th><th>Facility</th><th>Payload</th></tr></thead><tbody>\n"
     (str/join "\n" (map coordination-row coord-log))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Append-only audit ledger</h2>"
     "<table><thead><tr><th>Fact</th><th>Op</th><th>Facility</th><th>Disposition</th><th>Basis / summary</th></tr></thead><tbody>\n"
     (str/join "\n" (map ledger-row ledger))
     "\n      </tbody></table></section>"

     "</main></body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db  (run-demo!)
        f   (java.io.File. ^String out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f (render db))
    (println "wrote" out
             "-- ledger facts:" (count (store/ledger db))
             "| hard holds:" (count (filter #(= :governor-hold (:t %)) (store/ledger db)))
             "| coordination records:" (count (store/coordination-log db)))))
