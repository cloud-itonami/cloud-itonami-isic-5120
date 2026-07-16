# Contributing

`cloud-itonami-isic-5120` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real facility, cargo, shipper or safety-incident data.
- Keep shipment-record logging, ground-operation scheduling, maintenance-
  order coordination and safety-concern flagging behind the
  AirCargoGroundOpsGovernor.
- Treat ground-logistics-scheduling workflows as high-risk: add tests
  for facility/contractor verification, effect discipline, scope
  exclusion, escalation and audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "airworthiness", "weight and balance", "dangerous goods") -- phrase it
  as the finalization/execution ACTION (e.g. "finalize the airworthiness
  clearance", "sign off on the weight and balance", "accept the
  dangerous goods shipment"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip this
  actor's own legitimate `:flag-safety-concern` happy path -- see
  `airfreightops.governor/scope-excluded-terms`'s docstring.
- Never add an op, or a code path, that touches any flight-operations,
  piloting, or air-traffic-control function, or that finalizes an
  airworthiness clearance, a weight-and-balance sign-off, or a
  dangerous-goods acceptance determination, or that authorizes a flight
  to depart. This actor is GROUND LOGISTICS SCHEDULING ONLY,
  structurally, not as a rollout milestone.
- `:flag-safety-concern` must always escalate immediately to a human and
  must never be added to any phase's `:auto` set. Add/extend the
  structural test asserting this if you touch `airfreightops.phase`.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
