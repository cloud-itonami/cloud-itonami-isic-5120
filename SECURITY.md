# Security Policy

This project handles ground-logistics-scheduling and safety-concern
workflows for air-cargo facilities. Treat vulnerabilities as potentially
high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real facility, cargo or shipper data exposure
- authorization bypass
- AirCargoGroundOpsGovernor bypass
- audit-ledger tampering
- over-disclosure in safety-concern reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on facility/cargo/shipper data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real facility, cargo and shipper data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- This actor never touches flight operations, piloting, or air-traffic-
  control functions, and never authorizes a flight to depart -- any
  deployment integration that would let it do so is a security-relevant
  design defect, not a feature gap; report it as such.
