# Security

This actor gates reimbursement. Report privately to the maintainers through
the gftdcojp organization — not as a public issue — anything that lets a
proposal reach `commit-record!` without passing `keihi.governor/check`, or
that lets a HARD violation be reported as escalatable and so invites an
approver to wave through something no approval can pass.

No secrets belong in this repo. The store is a protocol; a deployment's
credentials live with its Store implementation, not here.
