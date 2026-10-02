# FleetTruth: Judge Brief

## The Problem

How can a fleet platform combine changing OEM telemetry formats without letting wrong units, missing fields or stale messages corrupt vehicle decisions?

An integration can keep returning HTTP 200 while becoming semantically wrong. A manufacturer changes battery percentage `42` into fraction `0.42`; another reports speed in mph. Unchecked parsing can produce false alerts, hide a real low-battery vehicle or show stale information as trustworthy.

## The Demonstration

FleetTruth retains the original event and only normalizes against an approved, exact-version contract. Unknown formats enter quarantine. An engineer inspects source paths and units, validates a proposed mapping against retained samples, explicitly approves it and replays the original events. For example, `soc_fraction: 0.07` becomes `socPct: 7`; the resulting alert retains its event and mapping evidence.

Duplicate events do not duplicate effects. Late/replayed events cannot replace newer state. The assistant retrieves tenant-scoped evidence and recommends review, but cannot approve a mapping or perform mutations.

## What Makes It Useful

- Integration engineers can explain a signal conversion and recover retained data after an OEM change.
- Fleet operators can distinguish current trustworthy state from quarantined or stale evidence.
- Administrators have role boundaries, tenant isolation, audit records and an explicitly limited local-erasure workflow.
- The UI supports repeated operational work: search, evidence inspection, version comparisons, recovery progress, report export, profile and day/night preferences.

These are demonstrated capabilities and proposed benefits, not measured customer savings.

## Engineering Evidence

| Area | Demonstrated locally |
| --- | --- |
| Runtime | Native and Docker; PostgreSQL, Kafka, Redis and pgvector; Cassandra integration test |
| Correctness | 63 backend tests, including real stores, Pact and Cucumber; zero failures/skips |
| UI | 20 browser tests per deployment, both themes and mobile/tablet/desktop layouts |
| Core coverage | Ten named classes exceed 80% line and instruction coverage |
| Resilience | Durable replay/outbox, independent sink circuit breakers, atomic read summaries |
| Observability | Authorized Prometheus scrape, Grafana, real HTTP/Kafka traces in Jaeger, JSON logs |
| Bounded latency | 90 reads at concurrency 3: p95 84.19 ms, p99 205.77 ms, zero observed errors |
| Model evaluation | Synthetic holdout F1 about 0.980 versus baseline 0.629; advisory only |
| Security checks | 13 HTTP checks per deployment; dependency/image scans; residual findings disclosed |

## Scope and Next Step

This is a working normalization/recovery prototype with a synthetic 100K-vehicle registry, not a completed 100K-events/sec production platform. The backend is a modular monolith. Production requires partitioned bulk storage/workers, sharded read summaries, HA, costed load/chaos testing, real identity/encryption and cross-store erasure. The final API image still reports 29 MEDIUM/LOW OS findings; full SAST/DAST and infrastructure scans remain pending.

The next product validation is a controlled pilot using authorized OEM specifications and labeled drift incidents. Measure onboarding effort, time to identify a changed contract, successful recovery rate and incorrect-alert rate against the previous integration workflow.

**Closing sentence:** FleetTruth makes vehicle decisions traceable to the source data, the approved interpretation and the person who authorized it.
