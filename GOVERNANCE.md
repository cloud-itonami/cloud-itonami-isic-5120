# Governance

`cloud-itonami-isic-5120` is an OSS open-business blueprint for
air-cargo ground-logistics scheduling coordination (ISIC Rev.5 5120 --
freight air transport).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered ground-handling facility, or
  a maintenance order naming an unverified/unregistered contractor, can
  never commit.
- the AirCargoGroundOpsGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, flight-operations/
  piloting/air-traffic-control content, airworthiness-clearance/weight-
  and-balance/dangerous-goods-acceptance finalization content, an op
  outside the closed allowlist) cannot be overridden by human approval.
- this actor never touches flight operations, piloting, or air-traffic-
  control functions in any way, and never authorizes a flight to
  depart.
- every shipment-record log, ground-operation schedule, maintenance-
  order coordination and safety-concern flag is auditable.
- `:flag-safety-concern` always escalates immediately to a human and is
  never eligible for auto-commit at any phase.
- facility, cargo and shipper data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review.

Certified operators can lose certification for:
- bypassing shipment-record, ground-operation-scheduling, maintenance-
  order or safety-concern policy checks
- mishandling facility, cargo or shipper data
- misrepresenting certification status
- failing to respond to security or safety incidents
