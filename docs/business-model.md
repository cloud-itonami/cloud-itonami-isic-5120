# Business Model: Air-Cargo Ground Logistics Scheduling Coordination

## Classification
- Repository: `cloud-itonami-isic-5120`
- ISIC Rev.5: `5120` -- freight air transport (cargo airlines and air
  freight forwarding)
- Social impact: safety, supply-chain resilience, transparency

## Customer
- independent air-cargo ground-handling operators and freight-
  forwarding coordinators needing an auditable operations-coordination
  platform
- multi-facility/multi-carrier operators needing consistent maintenance/
  scheduling/safety governance across a network
- programs that cannot accept closed, unauditable back-office platforms
  for cargo and manifest records

## Offer
- cargo/manifest/AWB (air waybill) record logging
- warehouse/ramp/loading-dock ground-operation scheduling coordination
- ground-equipment (GSE) maintenance procurement coordination with
  registered, verified contractors
- weight-and-balance/dangerous-goods (IATA DGR)/airworthiness-concern
  flagging for human (operating crew/cargo-safety officer) triage
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per facility/carrier
- support retainer with SLA

## Trust Controls
- `:air-cargo-ground-ops-governor` never lets a proposal for an
  unregistered/unverified ground-handling facility, or a maintenance
  order naming an unregistered/unverified contractor, commit or even
  escalate
- every proposal's `:effect` must be `:propose` -- a claim to directly
  actuate is a HARD, un-overridable block
- directly finalizing an airworthiness clearance, a weight-and-balance
  sign-off, or a dangerous-goods acceptance determination, authorizing a
  flight to depart, or any other flight-operations/piloting/air-traffic-
  control action is permanently out of scope, not a rollout milestone --
  the actor may only flag a concern for a human
- a `:flag-safety-concern` proposal ALWAYS escalates immediately, and a
  high-cost `:coordinate-maintenance-order` always requires human
  sign-off
- sensitive facility, cargo and shipper data stays outside Git
