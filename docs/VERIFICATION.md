# Verification Report

Recorded on 2 October 2026 (Asia/Calcutta). This report separates executed local checks from unfinished production requirements. Native environment: Windows, Java 25 compiling to Java 21, Maven 3.9.14, Node 22, H2 and Microsoft Edge. Docker uses Java 21, PostgreSQL/pgvector, Kafka and Redis. No live OEM integration or cloud deployment was used.

## Executed checks

| Check | Result | Evidence and scope |
| --- | --- | --- |
| Clean backend `mvn clean verify` | 63 passed; zero failures, errors or skips | `evidence/verification.json`; Surefire and Failsafe suites |
| Real integration stores | PostgreSQL, Kafka, Redis, pgvector and Cassandra passed | 18 PostgreSQL integration tests and one Cassandra test; included in the 63 total |
| Contract tests | Two Pact consumer and two provider tests passed | Signed-in profile contract and unauthenticated denial; not complete API contract coverage |
| BDD | Three Cucumber scenarios passed | Actual Gherkin runner and Spring step bindings, included in backend total |
| Critical coverage gate | All ten named classes passed | At least 80% lines and instructions for each; not every class/branch |
| Frontend production build | Passed | TypeScript and Vite; production bundle also served by Docker nginx |
| Native browser workflows | 20 passed | `evidence/browser-native-tests.json`; profile, appearance, permissions, mappings/replay, analytics and evidence |
| Docker browser workflows | 20 passed in 45 seconds | `evidence/browser-docker-tests.json`; same suite against port 3000 |
| Python ML tests | Two passed | Synthetic holdout and inference checks |
| HTTP security regression | 13 checks passed per environment | `security-smoke.json` and `docker-security-smoke.json`; bounded authenticated checks, not full DAST |
| Frontend dependencies | Zero known vulnerabilities reported | `frontend-dependency-audit.json`; npm audit, 169 dependencies |
| Python dependencies | Zero known vulnerabilities reported | Requirements audit: 24 resolved dependencies; project-environment audit also clean after pip update |
| Application container scans | Zero HIGH/CRITICAL findings in both final images | Trivy 0.75.0; API still has 13 MEDIUM and 16 LOW OS findings; web has zero findings |
| Java package scan | Zero known vulnerabilities in 136 inventoried packages | `java-dependency-audit.json`, extracted from the actual API image scan |
| Local observability | Metrics, Grafana health and HTTP/Kafka traces verified | `local-stack.json`; Prometheus target up, Jaeger receiving spans; ECS JSON logs enabled |
| Helm lint / Terraform validate | Passed in earlier local checks | No cloud plan/apply, Kubernetes deployment or remote CI run |
| SQL pagination plans | Identical ordered results before/after | H2 and real PostgreSQL `EXPLAIN ANALYZE`, each on 100K synthetic rows |
| Historical batch | 18,504 rows across four export pages | Earlier Parquet/DuckDB run in `batch-evaluation.json`; not a billion-row run |
| Product video | 114.88 seconds, silent, 1440x1000, 25 fps | Latest UI, profile/themes and recovery flow; five decoded frames visually inspected |

Browser checks cover all eight workspace views in both themes at widths 390, 768 and 1242; original workflows also use a 1440px desktop viewport. Profile tests cover session identity, role permissions, retry, logout, keyboard focus, persistent appearance, OS preference changes and cross-tab synchronization. This is not an accessibility certification or all-browser coverage. See [UI Design](UI_DESIGN.md).

The recovery tests handle an already-approved incident left by a prior run, and wait for actual API responses. Drift rendering no longer assumes an arbitrary five-second completion. Functional bounded waits are not latency-SLO proof. Earlier failed runs informed fixes and are not counted as passes. The regenerated video includes the current profile and theme controls.

## Critical coverage

Fresh JaCoCo report generated after both Surefire and Failsafe execution:

| Class | Instructions | Lines | Branches |
| --- | ---: | ---: | ---: |
| Normalizer | 92.77% | 91.87% | 82.95% |
| VinValidator | 98.05% | 95.65% | 91.67% |
| FleetService | 96.95% | 96.57% | 80.88% |
| ReplayWorker | 98.84% | 95.12% | 71.43% |
| PrivacyService | 93.59% | 93.55% | 50.00% |
| AssistantService | 98.84% | 98.80% | 81.25% |
| DriftModel | 95.53% | 97.01% | 77.27% |
| TelemetryRollups | 98.39% | 100.00% | 87.50% |
| ProjectionCircuits | 95.33% | 100.00% | 100.00% |
| StorageProjection | 84.19% | 84.85% | 91.67% |

These are explicit core-class results, not 80% coverage of the entire application or every branch. External OIDC and deployment-specific error paths still need their real environments. After a rerun, the generated report is the source of truth.

## Bounded performance

Latest native benchmark: **90 timed requests, concurrency 3, 100,000 registered vehicles and 335,880 stored events at the start**. One overview warm-up read; simulation continued during measurement. This small sample is not a load/soak result.

| Metric | Measured | PDF target | Outcome in this sample |
| --- | ---: | ---: | --- |
| Mixed API p50 | 13.34 ms | Not specified | Informational |
| Mixed API p95 | 84.19 ms | <200 ms | Within threshold |
| Mixed API p99 | 205.77 ms | <500 ms | Within threshold |
| Request errors | 0 / 90 | Not a sufficient reliability sample | No observed errors |

`evidence/local-api-benchmark.json` includes endpoint-specific timings. Transactional TOTAL/MINUTE/DAY rollups remove full-history dashboard aggregation; duplicate, replay, erasure, rollback and concurrent-writer tests check summary/ledger consistency in H2 and PostgreSQL. A tenant/OEM/recent-time index also bounds drift sample selection. These changes improve the local read path but do not establish fleet-scale write capacity.

Earlier results are preserved in `local-api-before-rollups.json`, `local-api-before.json` and `local-api-pre-aggregate.json`. One prior run had p95 569.33 ms and p99 689.77 ms. Data volume, cache state and concurrent work differed; these are diagnostic observations, not a controlled speedup claim.

The PostgreSQL temporary-table query experiment measured 3.111 ms before and 0.063 ms after an appropriate composite index, with identical 50-row ordered results. It used 100K synthetic rows and did not modify existing application data. This is a query microbenchmark, not proof of the full application workload.

**Not validated:** sustained 100K events/sec, 300K/sec for five minutes, billion-row batch, <2-second dashboard freshness, <5-second critical-alert latency, 99.9% availability, no-SPOF operation, soak or distributed kill recovery. Registry size and requested producer rate are not measured throughput.

## Security scope

Dependency updates and rebuilt base images eliminated HIGH/CRITICAL findings observed in the initial two application-image scans. Before/after reports are preserved. The final API image still reports **13 MEDIUM and 16 LOW Ubuntu-package findings**; the final Java package inventory and web image report zero findings. Scans are point-in-time database results, not proof of security.

Only the API and web application images were scanned, not Kafka, PostgreSQL, Redis or the monitoring images. The 13 HTTP checks cover authentication, authorization, tenant isolation, query parameter handling, CORS and response headers. Full authenticated ZAP, SAST execution, supply-chain signing and infrastructure-image remediation are not claimed. Kluster tools were unavailable; no Kluster review was performed.

## Remaining gates

1. **Submission actions:** supply official template/team details, review the demo and declarations, choose narration if needed, and upload before the official deadline. The chat-based working deadline is 2 October 2026 at 6:00 AM IST; confirm the portal time.
2. **Repository/CI:** no Git repository or remote was supplied. Publication, remote CI evidence and the reviewed `v1.0-submission` tag remain pending. The user approved local work only.
3. **Production security/privacy:** live OIDC, device mTLS, TLS, vault integration, encrypted stores, comprehensive internal/denial access auditing, external audit sink and cross-store erasure orchestration remain incomplete.
4. **Production architecture/observability:** separate bulk telemetry workers/storage, sharded summaries, HA topology and centralized durable log collection. Local Jaeger is transient and single-node.
5. **Production verification:** full sustained/burst/soak, latency, unique-event reconciliation, kill-recovery, billion-row batch and complete security testing in an approved costed environment.

No cloud deployment, remote repository, tag or CI URL has been fabricated. See [Requirement Traceability](REQUIREMENTS.md) and [Submission Checklist](SUBMISSION_CHECKLIST.md).
