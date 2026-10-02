# FleetTruth: Solution Document

## Executive summary

**Problem statement:** How can a connected-vehicle platform onboard and evolve multiple OEM telemetry formats without allowing incompatible units, missing fields, duplicates or late messages to corrupt fleet decisions?

**Solution:** A versioned normalization and recovery control plane. FleetTruth preserves source evidence, validates against an explicitly approved OEM contract, quarantines failures, detects drift, and provides a human-reviewed mapping/replay workflow. A low-battery alert links back to the event and conversion that produced it.

**Primary users:** integration engineers responsible for OEM contracts; fleet operators consuming trusted vehicle state; platform administrators responsible for access and privacy.

**Business value:** reduce time spent diagnosing format changes; recover useful data without asking a vehicle to retransmit it; prevent misinterpreted battery/location signals from reaching operational decisions. These benefits are hypotheses to measure with real operators, not claimed customer outcomes.

## Concrete scenario

A synthetic logistics fleet uses four manufacturers. Helix changes a battery field from `state_of_charge: 42` to `soc_fraction: 0.42`. Speed remains in miles/hour while the canonical API uses km/hour. An old parser could discard battery data, misread the fraction, or reuse a stale vehicle state.

FleetTruth raises a contract incident, keeps the payload, and marks affected state untrusted. An engineer inspects source paths and units, previews a proposed conversion against retained samples, and approves it. Replay converts `0.07` to `7%`; a moving vehicle produces a critical low-battery alert. Old replay events cannot overwrite a newer state or newer alert evidence. The audit trail records the engineer and mapping version.

Approval passing sample tests is **not** a mathematical proof of correct semantics. The engineer must compare the mapping with an OEM specification. In this synthetic scenario the source specification is known; no real OEM contract was supplied.

## Product and UX

The first screen is a usable operations workspace: fleet counts, measured ingestion, trust ratio, critical alerts, OEM health and recent actions. Dedicated views cover vehicle search with keyset pagination, alert lifecycle, integrations, mapping editor with raw/canonical comparison, recovery progress, historical analytics and audit.

Role enforcement exists on the API as well as in the UI. Reviewers cannot mutate mappings or inspect raw payloads and receive rounded coordinates. Engineers can validate and approve. Administrators can request local erasure with exact-VIN confirmation. The responsive frontend has keyboard focus handling, loading/empty/error states, disabled unauthorized actions, and exported operational reports.

Day, night and system appearance are persistent and synchronized between tabs. Either account avatar opens an authenticated user profile with account, preference and access details. Design references and original implementation boundaries are recorded in [UI Design](UI_DESIGN.md).

No invented business savings, fake live charts or hardcoded 100K/s performance indicators are shown. The seed registers 100K vehicles; the presentation simulator intentionally runs a small active subset. A separate sharded broker generator can cover the full fleet and request higher rates for real infrastructure experiments.

## Architecture and technology

| Layer | Implemented choice | Reason |
| --- | --- | --- |
| Web | React 19, TypeScript, Vite, TanStack Query, Recharts, Lucide | Inspectable, testable operations interface |
| API/domain | Java 21 target, Spring Boot, Spring Security, JDBC, Flyway | Typed contracts, explicit SQL/transactions, JWT authorization |
| Stream transport | Kafka adapter, VIN partition key, retry/DLT | Durable intake and ordered per-vehicle processing |
| Relational | H2 local; PostgreSQL deployment | ACID ownership, approvals, audit and recovery checkpoints |
| Cache / NoSQL | Redis projection; optional Cassandra time buckets | Disposable latest-state cache; append-oriented telemetry sink |
| Vector retrieval | pgvector exact cosine; local cosine fallback | Retrieve small static runbooks; exact search is sufficient for four documents |
| ML | Standardized logistic regression, exported coefficients | Auditable inexpensive advisory drift scoring |
| Assistant | Deterministic read-only tool router | Inspect actual evidence without giving generated text mutation privileges |
| Batch | PyArrow Parquet + DuckDB; separate Spark script | Reproducible local historical analytics and portable cluster execution |
| Delivery | Docker, Compose, Helm, AWS Terraform, GitHub Actions | Repeatable environments; no provider SDK in core business code |
| Resilience / observability | Resilience4j, transactional summary tables, Micrometer/OpenTelemetry, Prometheus, Grafana, Jaeger | Bounded reads, independent sink protection and inspectable HTTP/Kafka traces |

The running backend is a **modular monolith with adapters and scheduled workers**, not a completed distributed microservice estate. This limits demo complexity and gives atomic normalization/replay behavior. The production boundary plan separates ingestion, normalization, control-plane API, projection and analytics workers. There is no measured evidence that the current JDBC-per-event path meets 100K/s; removing bulk telemetry from the relational critical path is a mandatory scaling step.

## ML and guarded agent

The classifier uses missing-field ratio, numeric-type error ratio, range-error ratio, unapproved schema change, and relative battery-distribution shift. Training has 8,000 synthetic windows. Holdout has 2,400 windows with a different seed and lower fault severity, but the same fault families. Positive-class F1 is approximately **0.980**, compared with **0.629** for the declared fixed-threshold baseline. See `evidence/ml-evaluation.json` and Java/Python inference parity tests.

This is not validation on real OEM distributions. Benign route shifts are included, but synthetic assumptions can make classification artificially easy. Calibrate with labeled integration incidents, test unseen OEMs and faults, and monitor false positives before using it operationally. Model probability only recommends review; it cannot accept an invalid event or approve a mapping.

The assistant routes bounded questions to tenant-scoped tools, retrieves static runbooks, performs mapping dry runs, and records tool use. Mutation requests are declined. It is not an LLM or autonomous remediation agent. Lexical hashed vectors are not deep semantic embeddings. These choices are explicit, deterministic baselines with extension points rather than exaggerated AI claims.

## Scope and evidence

Clean backend verification passed 63 tests with no skips, including real PostgreSQL/Kafka/Redis/pgvector/Cassandra integration, Pact contracts and Cucumber acceptance. All ten named core classes exceed 80% line and instruction coverage. The same 20 browser tests passed against native and Docker deployments. Local metrics, traces, dependency/image scans and 13 HTTP security regression checks per deployment were executed.

The bounded native benchmark measured p95 84.19 ms and p99 205.77 ms across 90 requests at concurrency three. This is not a full-scale SLO claim. API container scanning still reports 29 MEDIUM/LOW OS findings, although neither final application image reports HIGH/CRITICAL findings. Full SAST/DAST, infrastructure-image scans, cluster scale, cloud deployment, production identity/encryption and cross-store erasure remain pending. Results and their exact scope are in `evidence` and the verification report.

See [Architecture](ARCHITECTURE.md), [ADRs](ADRS.md), [Security](SECURITY.md), [Algorithms and SQL](ALGORITHMS_SQL.md), [Operations](OPERATIONS.md), [Verification Report](VERIFICATION.md), and [Requirement Traceability](REQUIREMENTS.md).

## Declarations

- This work uses only synthetic data. Manufacturer names, vehicles, locations assigned to vehicles and driver identities are invented fixtures; they are not vehicle-owner records.
- Motorq is an industry reference in the supplied academic brief. No affiliation, endorsement, private API access or real OEM integration is claimed.
- Open-source dependencies/tools include Spring Boot/Security/Kafka, Kafka clients, PostgreSQL/Flyway, Cassandra driver, Redis, pgvector, React, TypeScript, Vite, TanStack Query, Recharts, Lucide, oidc-client-ts, scikit-learn, NumPy, PyArrow, DuckDB, HTTPX, Testcontainers, JUnit, Playwright, pytest, Cucumber, Pact, Resilience4j, OpenTelemetry, Prometheus, Grafana, Jaeger and Trivy. Dependency manifests and lockfiles identify actual versions; review their licenses before redistribution.
- AI assistance: OpenAI Codex assisted with architecture, implementation, test generation and documentation. The team must review, understand and take responsibility for the submission. No claim of unaided authorship is made.
- This document follows the information in the supplied 10-page PDF. Its separate solution template was not attached, so this is a substitute structure to transfer into that template when received.
