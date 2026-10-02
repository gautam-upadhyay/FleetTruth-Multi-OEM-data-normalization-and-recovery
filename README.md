# FleetTruth

**Reliable Multi-OEM Vehicle Intelligence**

FleetTruth detects OEM schema changes, quarantines untrusted telemetry, validates versioned mappings, and recovers the original events after human approval. The demo shows why trustworthy normalization matters: a fractional battery reading must not silently become a percentage or hide a critical vehicle alert.

Independent academic hackathon project. Motorq is an industry reference, not an affiliate or integration partner. All vehicles, OEM brands, drivers and telemetry are synthetic.

## Run locally

Prerequisites: Java 21+, Maven 3.9+, Node 22+, npm. Windows PowerShell:

```powershell
./scripts/start-local.ps1
```

The launcher builds, chooses free ports, starts background processes, and prints the workspace URL. Defaults: frontend `http://127.0.0.1:5173`, API `http://127.0.0.1:8080`. On the development machine, API port 8081 was selected because 8080 was occupied. Actual ports and process IDs are in `.runtime/servers.json`.

| Account | Password | Access |
| --- | --- | --- |
| `engineer@fleettruth.demo` | `FleetTruth2026!` | Mapping, replay and alerts |
| `viewer@fleettruth.demo` | `FleetTruth2026!` | Read-only, approximate locations |
| `admin@fleettruth.demo` | `FleetTruth2026!` | Engineer access plus local erasure |
| `other@fleettruth.demo` | `FleetTruth2026!` | Separate synthetic tenant |

Local credentials only work in local/test mode. Never expose this demo profile to the public internet. The native demo uses durable H2 storage and does not require Docker. It registers 100,000 vehicles; its live presentation stream deliberately rotates through 192 vehicles at approximately 12 events/sec. **Enrollment count is not a throughput result.**

The sun/moon control switches day and night appearance. Either account avatar opens the user profile with Account, Preferences and Access tabs. Preferences also offers System appearance; the choice is saved in this browser and synchronized between tabs. Account identity and permissions are read-only, backed by the signed-in session and `/api/auth/me`. See [UI design notes](docs/UI_DESIGN.md) for the public industry references and implementation boundaries.

```powershell
./scripts/restart-api.ps1 -Build
./scripts/stop-local.ps1
```

## Docker deployment

Generate local secrets once, then the stack starts in one Compose command:

```powershell
./scripts/configure-demo.ps1
docker compose up --build -d
```

Open `http://127.0.0.1:3000`. Compose uses PostgreSQL + pgvector, Redis, Kafka, API and web. It seeds 100K vehicles and sends the continuing simulation through Kafka. No database or broker port is exposed publicly. Docker Desktop must be running with Linux containers.

Optional Cassandra projection, after its health check is ready:

```powershell
docker compose --profile nosql up -d --wait cassandra
$env:CASSANDRA_ENABLED='true'
docker compose up -d api
```

Optional monitoring:

```powershell
./scripts/configure-demo.ps1 -Monitoring
$env:TRACING_ENABLED='true'
$env:TRACING_SAMPLE_RATE='1.0'
docker compose --profile observability up -d api prometheus grafana jaeger
```

Prometheus is at `http://127.0.0.1:9090`, Grafana at `http://127.0.0.1:3001`, and Jaeger at `http://127.0.0.1:16686`. Grafana uses user `fleettruth` and the generated password in local `.env`. Keep that file private. The local monitoring token expires after four hours; renew it and restart Prometheus. Tracing settings above apply to this PowerShell session; repeat them before a later Compose recreation to keep tracing enabled.

The complete core Compose stack, metrics and HTTP/Kafka traces were verified locally on 2 October 2026. Real Cassandra integration was verified in Testcontainers; its optional Compose service is not left running. Compose is a **single-node demo**, not an HA or encrypted production installation. Jaeger uses transient in-memory storage.

## Demo route

1. Overview: inspect real counts and the Helix contract incident.
2. OEM integrations: inspect signal quality and advisory drift probability.
3. Mapping studio: compare source `/soc_fraction` with canonical `socPct`; validate scale 100.
4. Approve the mapping explicitly, then replay quarantined events.
5. Recovery: watch the durable job finish. Alerts and audit show the recovered evidence.
6. Fleet assistant: ask about drift, fleet priorities or replay. It uses read-only audited tools.

After recovery, use **Inject schema change** to repeat the story with a new schema version. This is a demo scenario control, not a real OEM integration.

An actual [silent product recording](artifacts/demo/FleetTruth-demo.webm) is included: 1 minute 55 seconds, 1440x1000, showing the user profile, day/night modes, drift, validation, explicit approval, replay, alerts, evidence, assistant, audit and analytics. [Presentation notes](docs/DEMO_SCRIPT.md) and [submission checklist](docs/SUBMISSION_CHECKLIST.md) accompany it.

## Verification

```powershell
cd api
mvn verify
cd ../web
npm ci
npm run build
npm run test:e2e
cd ..
python -m pip install -r requirements.txt
python -m pytest ml -q
python scripts/collect-evidence.py
```

Browser tests require the local services. Windows tests use installed Microsoft Edge; Linux CI installs Chromium. Set `APP_URL=http://127.0.0.1:3000` to test Compose. Real PostgreSQL/Kafka/Redis/pgvector and Cassandra tests automatically skip without Docker; CI explicitly requires Docker first. Reports: `api/target/site/jacoco`, `web/playwright-report`, `evidence`.

The latest clean backend verification passed 63 tests with no skips, including real stores, Cucumber and Pact. The browser suite passed all 20 tests against both native and Docker deployments. The evidence collector enforces at least 80% line and instruction coverage for ten explicitly named critical classes. It reports all classes and skipped tests without treating them as passes. See the [Verification Report](docs/VERIFICATION.md) for measured performance, security findings and outstanding production gates.

For batch export or the bounded local API benchmark, set `FLEETTRUTH_TOKEN` to a local engineer access token; see [Operations](docs/OPERATIONS.md). Do not put tokens in version control.

## Project map

| Path | Purpose |
| --- | --- |
| `api/` | Java/Spring API, domain rules, simulator, Kafka processor, durable replay/outbox |
| `web/` | React/TypeScript operations workspace, responsive layouts, role-aware workflows |
| `ml/` | Reproducible synthetic training, baseline and holdout tests |
| `batch/` | Actual Parquet/DuckDB export; Spark cluster job |
| `tests/` | Acceptance specifications and k6 read workload |
| `infra/` | Docker, Helm/Kubernetes, AWS Terraform foundation, monitoring |
| `docs/` | Solution, architecture, requirements matrix, ADRs, security, operations and demo script |
| `evidence/` | Measured outputs, explicitly scoped |

## Submission status

The working local vertical slice is implemented and verified in native and Docker environments. It is **not proof of the PDF's full production requirements**: sustained 100K/s, 300K/s for five minutes, billion-row batch, multi-zone availability, full external erasure, live OIDC/mTLS/TLS infrastructure, cloud deployment, centralized log storage and full security/chaos runs remain unfinished. See [Requirements](docs/REQUIREMENTS.md).

The user selected **local only** and communicated a 6:00 AM deadline; the working interpretation is 2 October 2026, Asia/Calcutta, subject to confirmation against the official portal. The official template, team details and repository remote are missing. No repository was published, no cloud resources were provisioned, and no submission tag was created. See the [judge brief](docs/JUDGE_BRIEF.md) and [submission checklist](docs/SUBMISSION_CHECKLIST.md). Run `python scripts/package-submission.py` to rebuild the source/evidence/video ZIP with its SHA-256 manifest; generated credentials and runtime databases are excluded.
