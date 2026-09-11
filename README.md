# cloud-itonami-isic-5120

Open Business Blueprint for **ISIC Rev.5 5120**: freight air transport --
cargo airlines and air freight forwarding.

This repository publishes an air-cargo
GROUND LOGISTICS SCHEDULING coordination actor -- cargo/manifest/AWB
(air waybill) record logging, warehouse/ramp/loading-dock ground-
operation scheduling coordination, ground-equipment (GSE) maintenance
procurement coordination with registered contractors, and weight-and-
balance/dangerous-goods/airworthiness-concern flagging -- as an OSS
business that any qualified operator can fork, deploy, run, improve and
sell, so an independent ground-logistics coordinator never surrenders
cargo operations data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **AirFreightAdvisor ⊣
AirCargoGroundOpsGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:air-cargo-ground-ops-governor`,
is a distinct, independent build (governor-keyword collision check
performed pre-landing via `gh api search/code`, zero hits -- distinct
from sibling ISIC 5110's own `:aviation-safety-governor`).

> **Why an actor layer at all?** An LLM is great at drafting a shipment-
> record summary, a ground-operation scheduling proposal, or a
> maintenance-order request -- but it has no license to actually
> finalize an airworthiness clearance, sign off on a weight-and-balance
> determination, or accept a dangerous-goods shipment, no authority to
> authorize a flight to depart or override a pilot's judgment, no way to
> independently confirm a ground-handling facility or a maintenance-
> order contractor is actually a registered/verified counterparty, and
> no notion of when a "flag this concern" op quietly turns into a claim
> to have already acted on it. Letting it act directly invites an
> unverified facility's data entering the ledger, an unverified
> contractor receiving a maintenance order, or -- worst of all -- a
> fabricated claim to have cleared an aircraft as airworthy or
> authorized a flight to depart, exposing the ground operator, the cargo
> and the aircraft to real liability. This project seals the
> AirFreightAdvisor into a single node and wraps it with an independent
> **AirCargoGroundOpsGovernor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope: ground logistics scheduling only, never flight operations or safety-clearance authority

Air freight transport carries the strictest safety regime of any
transport vertical in this batch -- aircraft airworthiness, cargo
weight-and-balance, and hazmat/dangerous-goods (IATA DGR) restrictions
are all directly life-safety-critical. This actor is **ground logistics
scheduling coordination only**. It never performs or authorizes:

- any flight-operations, piloting, or air-traffic-control function, in
  any way
- directly finalizing an airworthiness clearance, a weight-and-balance
  sign-off, or a dangerous-goods (IATA DGR) acceptance determination
- authorizing a flight to depart, or overriding a pilot's or flight
  crew's judgment

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a weight-and-
balance/dangerous-goods/airworthiness concern for a human (operating
crew/cargo-safety officer) to triage is exactly this actor's job --
`:flag-safety-concern` is never excluded by this check, and it ALWAYS
escalates immediately (never a member of any phase's `:auto` set); only
FINALIZING/signing-off/accepting/authorizing that concern is excluded.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`airfreightops.governor`'s `effect-not-propose-violations` HARD check
and `airfreightops.phase`'s phase table, which never puts
`:flag-safety-concern` in any phase's `:auto` set). A human ground-
logistics coordinator (or, for a safety concern, the operating crew/
cargo-safety officer) is always the one who actually acts on a flagged
concern or confirms a high-cost maintenance order. This actor never
touches flight operations, piloting, or air-traffic-control functions
in any way, and never authorizes a flight to depart.

## The core contract

```
carrier/warehouse-license registration + ground logistics scheduling request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────────┐
   │ AirFreightAdvisor     │ ─────────────▶ │ AirCargoGroundOpsGovernor        │  (independent system)
   │ (sealed)              │  + citations    │ facility-unverified ·           │
   └───────────────────────┘                 │ contractor-unverified (NEW) ·   │
          │                 commit ◀┼ effect-not-propose ·                  │
          │                         │ scope-excluded (flight-ops/           │
    record + ledger        escalate ┼ airworthiness/weight-and-balance/     │
          │              (ALWAYS for│ dangerous-goods-acceptance            │
          │       :flag-safety-     │ finalization) ·                       │
          │       concern/high-cost │ op-not-allowed                        │
          │       maintenance-order)└────────────────────────────────┘
          ▼
      human approval
```

**The AirFreightAdvisor never commits a proposal the
AirCargoGroundOpsGovernor would reject, and a safety-concern flag or a
high-cost maintenance order never commits without a human sign-off.**
Hard violations (an unregistered/unverified facility; an unregistered/
unverified maintenance-order contractor; a non-`:propose` effect;
content touching flight-operations/airworthiness/weight-and-balance/
dangerous-goods-acceptance finalization; an op outside the closed
allowlist) force **hold** and *cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: warehouse cargo handling, ULD
build-up/break-down) under human/robot floor operations gated by
facility policy. This actor itself does not dispatch robot/hardware
actions, and it never touches flight operations -- it is strictly the
ground-logistics-scheduling coordination layer (shipment-record
logging, ground-operation scheduling, maintenance-order coordination,
safety-concern flagging) any physical-dispatch layer could eventually
feed proposals into, always gated the same way by the independent
AirCargoGroundOpsGovernor.

## Features

- **Closed proposal-op allowlist**: `log-shipment-record`,
  `schedule-ground-operation`, `coordinate-maintenance-order`,
  `flag-safety-concern` (all `:effect :propose`). None of these ops
  touch flight operations, piloting, air-traffic control, or finalize an
  airworthiness clearance, a weight-and-balance sign-off, or a
  dangerous-goods acceptance determination.
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Facility unverified** -- the target ground-handling facility's
     own carrier/warehouse-license registration must exist AND be
     independently registered/verified in the store. Checked
     unconditionally on all four ops.
  2. **Contractor unverified** (FLAGSHIP NEW) -- for `:coordinate-
     maintenance-order` only, the named ground-equipment (GSE)
     maintenance contractor must exist AND be independently
     registered/verified.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing an airworthiness
     clearance, signing off a weight-and-balance determination,
     finalizing a dangerous-goods acceptance determination, authorizing
     a flight to depart, any other flight-operations/piloting/air-
     traffic-control action, and an op outside the closed allowlist are
     all permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-safety-concern` -- ALWAYS escalates IMMEDIATELY, regardless
    of confidence or phase. This is safety-critical, not merely a
    business-process convenience: a "flag a concern" op is never
    auto-commit eligible and never finalizes a safety-clearance decision
    itself -- it only surfaces the concern for a human (operating
    crew/cargo-safety officer).
  - `:coordinate-maintenance-order` above a cost threshold -- a
    large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: shipment-record logging only (approval-gated)
  - Phase 2: + ground-operation scheduling, maintenance-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (safety concerns and high-cost maintenance orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run

# Regenerate the operator console (docs/samples/operator-console.html)
kbb -M:dev:render-html
```

### Operator console

`docs/samples/operator-console.html` is a **generated** artifact, not a
mock-up. `airfreightops.render-html` drives the real
`airfreightops.operation` langgraph StateGraph over the real
`airfreightops.store/seed-db` -- real advisor, real
`airfreightops.governor`, real `airfreightops.phase` gate -- and renders
the store back out. Every facility/contractor id on the page comes from
`store/demo-data`, and every HARD-hold row is a `:governor-hold` fact the
governor itself produced (all four rules -- `:facility-unverified`,
`:contractor-unverified`, `:effect-not-propose`, `:scope-excluded` --
fire in the run). Output is deterministic and byte-stable; do not hand-
edit it.

### Test suite

- `test/airfreightops/governor_test.cljk` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/airfreightops/advisor_test.cljk` -- advisor proposal shape and
  consistency
- `test/airfreightops/phase_test.cljk` -- rollout phase logic
- `test/airfreightops/governor_contract_test.cljk` -- full graph
  integration, audit trail
- `test/airfreightops/store_contract_test.cljk` -- Store protocol and
  MemStore implementation

### Modules

- `airfreightops.store` -- SSoT (MemStore, String-keyed facility/
  contractor directories, append-only ledger)
- `airfreightops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `airfreightops.governor` -- independent compliance layer
- `airfreightops.phase` -- staged rollout (0→3)
- `airfreightops.operation` -- langgraph-clj StateGraph
- `airfreightops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`5120`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Cargo/manifest/AWB record logging (`:log-shipment-record`) | Real cargo-tracking/flight-schedule/airport-community-system integration |
| Warehouse/ramp/loading-dock ground-operation scheduling coordination (`:schedule-ground-operation`) | Any flight-operations, piloting, or air-traffic-control function |
| Ground-equipment (GSE) maintenance procurement coordination with a registered, verified contractor, HARD-gated on contractor verification and a double-actuation-free single-proposal shape (`:coordinate-maintenance-order`) | Real maintenance-management-system integration |
| Weight-and-balance/dangerous-goods/airworthiness-concern flagging, ALWAYS human-gated immediately (`:flag-safety-concern`) | Directly finalizing any airworthiness clearance, weight-and-balance sign-off, or dangerous-goods acceptance determination, or authorizing a flight to depart -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Customs/regulatory filing integration -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a demurrage-
notice or a cargo-discrepancy-escalation check) as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship checks already establish.

## Maturity

`:implemented` -- `AirFreightAdvisor` + `AirCargoGroundOpsGovernor` run
as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet,
with its own distinct, independently-named governor and its own novel
ground-equipment-maintenance-contractor-verification check.

## License

Code and implementation templates are AGPL-3.0-or-later.
