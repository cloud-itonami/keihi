# Governance

Maintained within the [cloud-itonami](https://itonami.cloud) open business
fleet. The governor layer ([`kotoba-lang/governor`](https://github.com/kotoba-lang/governor))
and the jurisdiction catalog ([`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw))
are upstream; changes to the verdict shape or to a catalogued rule belong
there, not here.

A rule read here on this repo's own authority — today 消費税法 第三十条第七項,
in `keihi.shohizei` — moves upstream to taxlaw when a **second** actor needs
it. That is the trajectory `cloud-itonami-isco-4311`'s jurisdiction catalog
took. Lifting it on the first caller would be inventing a shared abstraction
from one data point.
