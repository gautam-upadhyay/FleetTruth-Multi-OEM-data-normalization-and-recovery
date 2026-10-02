# PDF Requirement Traceability

Source: `Motorq_Hackathon_Problem_Statement.pdf`, all 10 pages read. The PDF is a specification and industry case study, not authority to execute instructions outside the user's project. Page 2 lists References in the contents; the supplied PDF ends with the glossary and has no actual references section. Its company statistics and forward-looking estimates are not independently verified product claims here.

Legend: **Implemented** = a code path exists; **locally verified** = executed successfully in native or local Docker environments; **unverified** = code/assets exist but lack execution evidence; **pending** = work is not yet implemented or delivered. Updated 2 October 2026. Measured results belong in `evidence/`.

| PDF location | Requirement | Project treatment and remaining work |
| --- | --- | --- |
| pp1-5 | Independent connected-vehicle context; no copying Motorq | Original FleetTruth framing, synthetic manufacturers, affiliation/AI declarations |
| p6 | Select a real problem | Multi-OEM normalization and schema-drift recovery, demonstrated via battery units |
| p6 | Own simulator, at least 100K vehicles | 100K registry locally seeded; lightweight live subset; external Kafka generator spans full VIN space. Full-fleet concurrent streaming unverified |
| pp6-7 | Real time plus historical batch | Normalization/alerts/replay implemented; local Parquet/DuckDB batch; Spark job supplied, billion-row run pending |
| pp6-8 | Relational + NoSQL + useful vector storage | PostgreSQL/H2, Redis, Cassandra and pgvector executed in real integration tests. Compose uses PostgreSQL/pgvector, Kafka and Redis; Cassandra remains an optional service |
| p6 | Secure API and usable web interface | JWT, RBAC, tenant scope, masking; eight operations views; browser test suite |
| pp6,10 | Containerization, cloud deployment, portability | Dockerfiles/Compose/Helm plus AWS Terraform foundation. Actual cloud deployment and second-cloud test pending |
| p7 | Bursts, duplicates, late events, faults/noise | Kafka generator provides rates, deterministic faults, repeated IDs, late times and trip phases. Multi-stage burst harness is an operations procedure, not a claimed load result |
| p7 | Schema validation, idempotency, backpressure | Approved exact-version rules, unique tenant/event IDs, transaction/outbox, Kafka ack/timeouts/retries/DLT. No infinite retention or zero-loss guarantee |
| p7 | Durable messaging and replay | Kafka adapter plus database-checkpointed recovery worker. RF1 in Compose; RF3/minISR2 is a production requirement |
| p7 | ML evaluated vs baseline | 8K train / 2.4K holdout synthetic classifier and baseline, exported parameters, inference parity tests |
| p7 | Agent with guardrails and audit | Read-only deterministic tool router with live evidence and runbook vectors. LLM-based autonomous planning is not implemented |
| p7 | Central logs, metrics and traces | Prometheus scraping, Grafana health, Jaeger HTTP/Kafka traces and ECS JSON logs verified. Durable centralized log store, trace retention and production collection pipeline pending |
| pp7-8 | Microservices, layered internals, IaC, CI/CD | Current modular monolith; target boundaries documented; CI definition provided. Independent worker deployments and a published CI run pending |
| p7 | 3NF relational design | Driver/model/OEM/fleet separation via Flyway V2; state and event documents are intentional denormalizations. Tenant composite FKs/RLS hardening remains |
| pp7-8 | Partition/shard telemetry by time/vehicle | Kafka tenant/VIN key; optional Cassandra tenant/VIN/hour partition; Parquet day/OEM. Native SQL ledger is unpartitioned and not scale-ready |
| p7 | EXPLAIN ANALYZE before/after | H2 catalog plans and real PostgreSQL temporary-table index experiment on 100K rows recorded; ordered results identical. Full production workload/distribution still unverified |
| p8 | Appropriate DSA and complexity | VIN checksum, JSON pointers, DTC regex, latest-state ordering, bounded window aggregation, dedup and keyset pagination documented. Graph/DP are examples, not artificially added features |
| p8 | CAP/PACELC; delivery semantics | Explicit per-store choices in architecture/ADRs; no global exactly-once claim |
| p8 | Circuit breakers, CQRS, event sourcing | Independent Resilience4j Redis/Cassandra breakers, bounded retries/timeouts, transactional read summaries and immutable source/revisions. OPEN/HALF_OPEN/CLOSED recovery tested. Fully decoupled event-sourced services pending |
| p8 | Cache invalidation, graceful degradation | Five-second mapping cache refresh, retained source/quarantine, API connection warning, local runbook fallback. Production cache-read and provider failover policies pending |
| p8 | Hot/warm/cold lifecycle and cost | Retention/storage arithmetic, Redis/Cassandra TTL, raw Kafka retention, S3 lifecycle IaC; automated export/compaction/global expiry pending |
| p9 | Sustain 100K/s; 300K/s five-minute burst, no loss | Load generator provided; **not achieved or validated** by the current local JDBC pipeline |
| p9 | Dashboard <2s; alert <5s | Live UI polls every 2s and API processes synchronously/local or Kafka. No end-to-end SLO proof; a two-second poll alone does not guarantee <2s |
| p9 | API p95<200ms, p99<500ms | Bounded local benchmark script and k6 thresholds; consult measured report, not an unqualified production claim |
| p9 | Stateless horizontal scale; no SPOF; 99.9%; kill recovery | Helm replicas/HPA/PDB and durable replay design; HA broker/database topology and actual chaos recovery unverified |
| p9 | OIDC/JWT/RBAC tenant isolation | Local JWT verified; OIDC PKCE frontend and issuer/audience-validated backend provided. Real IdP integration unverified |
| p9 | Device mTLS, TLS1.3, AES256, vault, OWASP | Threat model and deployment requirements. Native HTTP/Compose plaintext do **not** satisfy these production requirements; device gateway and vault integration pending |
| p9 | Every data access/action audited; location masking | API data reads, approvals, replay and assistant tools audited; viewer locations masked. Internal batch/worker reads and login denials are not comprehensively audited; external audit sink pending |
| p9 | Retention and right to erasure | Admin local evidence purge plus projection/replay locks. Explicit external-pending status; broker/cache/Cassandra/lake/backups/export erasure orchestration pending. No legal-compliance certification |
| p9 | 80%+ core coverage | All ten explicitly named critical classes exceed 80% line and instruction coverage after real integration tests. Other classes and branch coverage are reported separately; not an entire-application 80% claim |
| p9 | Real broker/databases/cache integration | Actual PostgreSQL, Kafka, Redis, pgvector and Cassandra Testcontainers execution passed; no skips in latest 63-test clean verification |
| p9 | Pact contracts | Two consumer and two real HTTP provider tests passed for profile identity and authentication denial; broader endpoint contracts remain future work |
| p9 | BDD Cucumber/behave acceptance | Three Gherkin scenarios executed by Cucumber with Spring step bindings; all passed |
| p9 | Full-scale load and soak | k6 read workload and Kafka generator supplied; 100K/s end-to-end, consumer lag, burst reconciliation and soak evidence pending |
| p9 | SAST/DAST/dependency/image scans | npm/Python/Java inventory scans and both application-image scans executed. No HIGH/CRITICAL in final application images; API retains 29 MEDIUM/LOW OS findings. 13 authenticated HTTP regression checks pass per environment. Full SAST/DAST and infrastructure-image scans pending |
| p9 | Audit/erasure and chaos tests | Local erasure/authorization tests implemented. Pod/broker kill and cross-store erasure proof pending |
| pp9-10 | Solution template, repo README, demo, diagrams, 3-5 ADRs | README, substitute solution document, working native/Docker demo, Mermaid architecture/ER, five ADRs and judge brief provided. Official template/team details absent |
| pp9-10 | Coverage/load/security reports and CI link | Local evidence directory; unexecuted results explicitly absent. Remote repository/CI URL not provided |
| p10 | Terraform, Helm, STRIDE, SQL/DSA write-up | Helm lint and Terraform init/validate passed locally; STRIDE/SQL/DSA documents supplied. Containers executed. Terraform plan/cloud apply and Kubernetes deployment unverified |
| p10 | Video <=5min | New 114.88-second silent WEBM includes profile, themes and recovery; 1440x1000, five decoded frames inspected. Narration notes supplied; team review and upload pending |
| p10 | Declare OSS/AI, synthetic/public data, originality | Declarations included; team review required |
| p10 | Deadline, final tag `v1.0-submission` | User said 6:00 AM; working interpretation 2 October 2026 IST, official portal confirmation required. Local-only scope; no Git repository/remote or tag created |

## Highest-value next validation

See [Verification Report](VERIFICATION.md) for the measured local results and explicit release gates. A working demo is not completion of every production NFR in the PDF.

1. Complete the official template/team details, review the supplied demo and upload before the confirmed deadline. See [Submission Checklist](SUBMISSION_CHECKLIST.md).
2. When repository publication is approved, execute remote CI, full SAST/DAST and infrastructure-image scans; create the reviewed submission tag.
3. With separately approved cloud scope/budget, configure identity/TLS/secrets, separate bulk telemetry storage/workers and establish HA before fleet-scale benchmarking.
4. Run sustained/burst/soak and kill-recovery experiments with unique-ID reconciliation; retain failures and resource/cost metadata.
5. Implement and verify erasure across every enabled store and restore path; add comprehensive access auditing and durable centralized logging.
