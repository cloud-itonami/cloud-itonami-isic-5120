# Operator Guide

## First Deployment
1. Register operator, ground-handling facilities and maintenance
   contractors; independently confirm each facility's carrier/
   warehouse-license registration and each contractor's registration
   before seeding `airfreightops.store`.
2. Import existing cargo/manifest/AWB, ground-operation-scheduling and
   maintenance-order history.
3. Run read-only shipment-record-logging and ground-operation-scheduling
   dry-runs (Phase 0-1).
4. Configure the rollout phase and the `coordinate-maintenance-order`
   cost-escalation threshold for human sign-off paths.
5. Publish a dry-run safety-concern flag and audit export.

## Minimum Production Controls
- facility-registration/verification check before ANY proposal for that
  facility
- contractor-registration/verification check before ANY `:coordinate-
  maintenance-order` proposal
- governor gate on every proposal before commit
- human sign-off for `:flag-safety-concern` (always, immediately) and
  high-cost `:coordinate-maintenance-order` proposals
- audit export for every commit, hold and approval
- backup manual back-office process
- this actor MUST NOT be wired to any flight-operations, piloting, air-
  traffic-control, airworthiness-clearance, weight-and-balance-sign-off,
  or dangerous-goods-acceptance system as a finalizing authority -- it
  may only feed proposals to a human operating crew/cargo-safety officer

## Certification
Certified operators must prove facility/contractor-verification
discipline, governor-bypass resistance, evidence-backed safety-concern
reporting and human (operating crew/cargo-safety officer) review for
every escalation-gated action.
