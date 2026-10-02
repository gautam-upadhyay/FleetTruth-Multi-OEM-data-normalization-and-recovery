# Operations and Verification

## Native runtime

`scripts/start-local.ps1` builds and selects free ports. It records exact process IDs, uses hidden background windows and keeps logs in `.runtime`. Restart only the API with `scripts/restart-api.ps1 -Build`, or the frontend with `scripts/restart-web.ps1`. `stop-local.ps1` validates the recorded command lines before stopping processes. Do not kill all Java/Node processes.

H2 data lives at `api/.runtime/fleettruth.mv.db`. It persists approvals, events and jobs between restarts. Do not remove it while running. A fresh demo database should be created by configuring a separate `DATABASE_URL`, not by destroying a working database without approval.

## Get a local token without printing it

```powershell
$state=Get-Content .runtime/servers.json -Raw | ConvertFrom-Json
$login=Invoke-RestMethod "$($state.apiUrl)/api/auth/login" -Method Post -ContentType 'application/json' -Body (@{email='engineer@fleettruth.demo';password='FleetTruth2026!'}|ConvertTo-Json)
$env:FLEETTRUTH_TOKEN=$login.token
python scripts/local-benchmark.py --api $state.apiUrl
python batch/export_analytics.py --api $state.apiUrl
Remove-Item Env:FLEETTRUTH_TOKEN
```

The benchmark makes only 90 timed reads at concurrency 3 by default, after one overview warm-up read. It records the starting vehicle/event counts, endpoint-specific latency, request errors and nearest-rank percentiles. Its timings include client/network time, run on one laptop, and are not a high-load SLO result. Avoid running builds, browser tests or batch exports simultaneously. The synthetic stream keeps running, so repeated runs do not have identical data size or cache state. Preserve failed results rather than reporting only the fastest run.

The batch export is bounded to 100K rows by default, pages by ID and fixes a received-time cutoff. A concurrent replay may still change row status; pause simulation and finish recovery before a repeatable demo export. The exported Parquet remains synthetic, but production exports would require access control, encryption and deletion tracking.

`python scripts/collect-evidence.py` refreshes backend totals and line/instruction/branch coverage from actual Surefire, Failsafe and JaCoCo reports. It fails when reports are absent, tests failed, or any of the ten declared critical classes falls below 80% line or instruction coverage. Run `mvn clean verify` so the coverage report includes integration and Pact provider tests. Docker skips remain skips. CI runs this gate after backend verification. Browser tests write `evidence/browser-tests.json` as well as the HTML report; preserve a separately named JSON copy per tested deployment.

Local checks and packaging:

```powershell
python scripts/security-smoke.py --api http://127.0.0.1:8081
python scripts/security-smoke.py --api http://127.0.0.1:8082 --output evidence/docker-security-smoke.json
python scripts/postgres-plans.py
python scripts/capture-local-evidence.py
python scripts/package-submission.py
```

The PostgreSQL plan experiment uses temporary synthetic tables in the running Compose database. The evidence capture requires the optional observability services plus existing application-image scan JSON files. The packager writes `artifacts/submission/FleetTruth-submission.zip`, includes a SHA-256 manifest, validates every entry, and writes a separate archive summary. It excludes `.env`, runtime data, dependency caches, raw test XML and browser traces. These exclusions reduce accidental credential exposure; the team must still review all public artifacts.

## Kafka load generator

Build the API JAR; run `java -jar api/target/fleettruth-api-1.0.0.jar --load-generator`. This selects a standalone producer instead of starting the API. Environment settings:

| Setting | Default | Meaning |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Reachable broker addresses |
| `KAFKA_PROPERTIES` | unset | Optional local Java properties file for TLS/SASL; never commit credentials |
| `EVENT_RATE` | 1000 | Requested per-generator rate, not guaranteed throughput |
| `DURATION_SECONDS` | 60 | Producer run duration |
| `GENERATOR_SHARDS` | 1 | Number of distributed generators |
| `GENERATOR_ID` | 0 | Unique index below shard count |
| `VEHICLES` | 100000 | Must match enrolled synthetic VIN space |

Example full-scale **test plan**, not a passing result: ten generators at10K/s for a sustained phase, then ten at30K/s for300seconds, then back to10K/s for drain measurement. Separate one-hour or longer soak. Use a private reachable broker network; Compose does not expose Kafka to the host by default. The generator uses synthetic faults, late times and deliberate repeated event IDs. It reports broker acknowledgements, not downstream success. It does not itself orchestrate the multi-stage run.

Record requested/actual rate, acknowledged/failed producer sends, payload bytes, unique IDs, consumer lag, accepted/quarantined/DLT counts, end-to-end event latency, duplicate counts, node CPU/memory/GC, disk/network and retention headroom. Reconcile unique source IDs with accepted+quarantine+explicitly failed records. Duplicates are not lost messages; a DLT is not a successful business decision. Stop the run before disk exhaustion.

The current REST API guard is intentionally low-rate, and the SQL normalization path is not suitable for the PDF's full target. Do not raise the guard and call the design scaled. First implement the production storage/worker split in the architecture document.

## Failure and chaos procedure

Use an isolated synthetic staging cluster, not a real fleet. Capture a baseline, producer run ID, broker offsets and replay checkpoint. Delete one selected **staging** normalizer pod; observe another worker continuing without duplicate effects. Kill a selected broker only on an RF3/minISR2 staging cluster; verify producer retries, leadership changes, lag drain and unique-ID reconciliation. Single-broker Compose cannot prove no-SPOF availability.

For replay recovery, start a job larger than one page, interrupt its worker, restart and verify the same persisted job ID/cursor completes. Check all source events, revisions, alert evidence and audit counters. For external projections, disrupt a sink, confirm durable pending outbox rows/backoff, restore it and verify idempotent catch-up. These procedures are not recorded as executed chaos evidence.

## Monitoring

Prometheus endpoint requires ADMIN. Suggested queries:

```promql
sum(rate(fleettruth_events_total[1m])) by (status)
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
```

Start local observability after the core Compose API is ready:

```powershell
./scripts/configure-demo.ps1 -Monitoring
$env:TRACING_ENABLED='true'
$env:TRACING_SAMPLE_RATE='1.0'
docker compose --profile observability up -d api prometheus grafana jaeger
```

Open Prometheus on port 9090, Grafana on 3001 and Jaeger on 16686, all loopback only. Grafana username is `fleettruth`; its generated password stays in `.env`. The admin scrape token expires after four hours: rerun the monitoring configuration and restart Prometheus. Tracing variables above are shell-local; reapply them before recreating the API. Jaeger v2 exposes query APIs under `/api/v3`, and stores local traces in memory. ECS JSON console logs are enabled using `FLEETTRUTH_LOG_FORMAT=ecs` by default. Do not copy `.env` or `.runtime/prometheus-token` into submissions.

Prometheus, Grafana health and actual HTTP/Kafka traces have been verified. Use a service account/short-lived bearer integration in production, not the demo admin token. Add consumer lag, quarantine age, oldest outbox age, replay stuck time, DLQ rate, database saturation and end-to-end event-time histograms. Kafka exporter, durable trace retention and centralized log storage remain pending. Protect metrics from public access.

## Cloud deployment checklist

Terraform is an AWS **foundation**, not a complete one-click production stack. It creates private EKS nodes across supplied subnets, an explicit operator access grant, KMS and a private encrypted archive bucket. It requires existing networking and a chosen currently supported Kubernetes version. It does not provision managed PostgreSQL, Kafka, Redis, a vault, device gateway or the archive ingestion pipeline.

```text
terraform -chdir=infra/terraform/aws init
terraform -chdir=infra/terraform/aws plan
helm lint infra/helm/fleettruth
helm template fleettruth infra/helm/fleettruth
```

Review a costed plan before applying. Private EKS requires a connected operator network and endpoints/NAT for image pulls. Configure encrypted remote state and scoped CI identity. Create the runtime secret containing database/identity/broker/cache settings using a secret manager; never commit its values. Set production Kafka RF3 and minISR2, provision approved schemas/tenants before ingestion, install Metrics Server for HPA, and configure TLS, ingress and network policy CIDRs. The default chart has no external egress allowance until those CIDRs are provided. Update web CSP for the IdP.

For GCP/Azure, reuse OCI images, Helm manifests, SQL migrations, topic names and Parquet contracts. Replace EKS/VPC/KMS/S3 provisioning and service endpoints with the corresponding provider infrastructure. That is a portability design, not a completed second-cloud deployment test.

## Submission release

The user selected local-only work and stated a 6:00 AM deadline; the working interpretation is 2 October 2026 IST, to confirm against the official portal. Repository remote, official template and team details are missing. Review the source bundle, complete those details and upload the submission. Publish a repository/CI and create `v1.0-submission` only after authorization and review of the exact commit. The supplied recording is a native synthetic workflow, not a cloud or full-scale test. No remote CI URL is claimed. See [Submission Checklist](SUBMISSION_CHECKLIST.md).
