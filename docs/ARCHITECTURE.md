# Architecture and System Design

## Running data flow

![Running Data Flow Architecture](diagrams/running-data-flow.svg)

```text
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                            RUNNING DATA FLOW ARCHITECTURE                                              │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

 [ Synthetic Simulator / Generator ]
                 │
                 ▼
        [ Intake Adapter ]
           │          │
 (native)  │          │ (Compose mode)
           ▼          ▼
           │     [ Kafka Raw VIN Partitions ]
           │          │
           │          │ (ordered stream: tenant:VIN)
           ▼          ▼
   ┌────────────────────────────────┐
   │   Deterministic Normalization  ├──────────► [ Quarantine / DLT Incident ]
   └────────────────┬───────────────┘
                    │ (atomic commit)
                    ▼
   ┌────────────────────────────────┐ ◄───────── [ Mapping Preview & Approval ] ◄─── [ Spring JWT API ] ◄── [ React UI ]
   │      PostgreSQL / Local H2     │                                                        │
   │  (Authoritative ACID Ledger)   │ ───────────────────────────────────────────────────────┤
   └────┬───────────────────────┬───┘                                                        ▼
        │ (SKIP LOCKED)         │ (pending rows)                                  [ Read-Only Assistant Tools ]
        ▼                       ▼                                                            │
 [ Replay Worker ]     [ Outbox Worker ]                                            ┌────────┴────────┐
        │                       │                                                   ▼                 ▼
        └──────────────┐        ├──────────► [ Redis State Projection (24h TTL) ] [ Advisory ML ] [ pgvector / Runbooks ]
        (retry/replay) ▼        │
   [ Re-enter Normalizer ]      ├──────────► [ Cassandra Telemetry Buckets ]
                                │
                                ▼
                   [ Bounded Historical Export ] ──► [ Partitioned Parquet ] ──► [ DuckDB / Spark Cluster ]
```


The normalizer, intake adapter, API and workers currently share one Spring process. Dashed/imaginary services are not implied by this diagram. PostgreSQL, Kafka, Redis, pgvector and Cassandra adapters have passed real Testcontainers integration; the core Compose stack also passed the browser suite. API reads currently use SQL, not Redis; cache serving is a future optimization, and Redis does not define source-of-truth semantics.

## Production boundary plan

Split deployable ingestion gateways, normalizer workers, transactional control API, projection workers, historical jobs and assistant tools. Ownership/approval/audit remain in PostgreSQL. The event body and canonical history move to Kafka plus Cassandra/time-series storage and a partitioned object lake; only incident summaries/checkpoints belong in the control database. Maintain a bounded schema cache fed by approval events. The prototype already has transactional read summaries; production needs independently partitioned stream-maintained read models.

The current normalizer does several relational writes per event. Horizontal pod scaling alone will not remove that bottleneck. Partition/shard bulk telemetry before attempting the published target; measure per-partition capacity and skew before choosing counts.

## Read summaries and resilience

Flyway V8 backfills TOTAL, MINUTE and DAY counters from the existing event ledger. New ingestion, replay status changes and local erasure update the summaries in the same SQL transaction as their source changes. Unique event IDs prevent duplicate increments. Charts, OEM quality and historical reports read bounded summary rows instead of scanning the full raw history. Tests compare summaries with ledger counts after duplicate delivery, concurrent writes, rollback, replay and erasure in both H2 and PostgreSQL. V9 adds a tenant/OEM/recent-time index for drift samples.

The TOTAL row is a per-tenant/OEM write contention point. This is a useful local read optimization, not a scalable distributed counter at 100K events/sec. Production must shard counters or derive them asynchronously from partitioned streams, expose projection lag and reconcile against durable source IDs.

Redis and Cassandra writes have independent Resilience4j circuit breakers: a ten-call window, minimum five calls, 50% failure threshold, 80% slow-call threshold at two seconds, 30 seconds OPEN and two HALF_OPEN probes. Failed/rejected sink calls leave the durable outbox pending for later retries. Store failure does not falsely mark a projection delivered. Tests verify OPEN rejection, HALF_OPEN recovery and isolation between the two breakers; distributed failure/chaos is still unverified.

Micrometer/OpenTelemetry exports HTTP, scheduler and Kafka observations over OTLP to local Jaeger. Prometheus scrapes authorized metrics, Grafana exposes the configured dashboard, and ECS JSON console logs include structured fields. Jaeger is in-memory and there is no centralized durable log index yet. Sampling is 10% by default; local verification used 100% temporarily, not a production recommendation.

## Relational model

![Relational Model ERD](diagrams/relational-data-model.svg)

```text
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                        RELATIONAL & TELEMETRY DATA MODEL (ERD)                                         │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

 ┌───────────────────────────┐                           ┌───────────────────────────┐
 │          TENANTS          │                           │           OEMS            │
 ├───────────────────────────┤                           ├───────────────────────────┤
 │ PK  id                    │                           │ PK  id                    │
 │     name                  │                           │     name, color           │
 └───┬───────┬───────┬───────┘                           └───┬───────────────────┬───┘
     │ 1     │ 1     │ 1 (approves)                          │ 1                 │ 1 (versions)
     │       │       └───────────────────────────┐           │ (manufactures)    │
     │       │                                   │           │                   │
     ▼ N     ▼ N                                 │           ▼ N                 ▼ N
 ┌────────┐ ┌────────┐                           │       ┌──────────────────┐ ┌───────────────────────────┐
 │ FLEETS │ │DRIVERS │                           │       │  VEHICLE_MODELS  │ │         MAPPINGS        │
 ├────────┤ ├────────┤                           │       ├──────────────────┤ ├───────────────────────────┤
 │ PK id  │ │ PK id  │                           │       │ PK id            │ │ PK  id                    │
 │ FK ten │ │ FK ten │                           │       │ FK oem_id        │ │ FK  tenant_id, oem_id     │
 └───┬────┘ └───┬────┘                           │       │    name, powtrn  │ │     schema_ver, rules     │
     │ 1        │ 0..1                           │       └────────┬─────────┘ └───┬───────────────────────┘
     │ contains │ assigned                       │                │ 1             │ 1
     ▼ N        ▼ N                              │                │ identifies    │ interprets
 ┌───────────────────────────────────────────────┼────────┐       │               │
 │                   VEHICLES                    │        │ ◄─────┘               │
 ├───────────────────────────────────────────────┤        │                       │
 │ PK  vin                                       │        │                       │
 │ FK  tenant_id, fleet_id, model_id, driver_id  │        │                       │
 │     registration                              │        │                       │
 └───┬───────────────────────────────────────┬───┘        │                       │
     │ 1 (projects)                          │ 1 (emits)  │                       │
     ▼ 1                                     ▼ N          │                       │
 ┌───────────────────────────────────────┐ ┌──────────────┴───────────────┐       │
 │            VEHICLE_STATE              │ │          RAW_EVENTS          │       │
 ├───────────────────────────────────────┤ ├──────────────────────────────┤       │
 │ PK  vin                               │ │ PK  (tenant_id, id)          │       │
 │ FK  vin                               │ │ FK  vin, oem_id, schema_ver  │       │
 │     event_time, speed_kmh, soc_pct    │ │     event_time, payload      │       │
 │     odometer_km, lat, lon, quality    │ │     normalized, status       │       │
 └───────────────────────────────────────┘ └───┬──────────────┬───────────┘       │
                                               │ 1            │ 1                 │
                                  evidences    │              │ normalizes        │
                                               ▼ N            ▼ N                 ▼ N
                                           ┌─────────┐    ┌─────────────────────────────────────────┐
                                           │ ALERTS  │    │           TELEMETRY_REVISIONS           │
                                           ├─────────┤    ├─────────────────────────────────────────┤
                                           │ PK id   │    │ PK  id                                  │
                                           │ FK vin  │    │ FK  (tenant_id, event_id), mapping_id  │
                                           │ FK ev_id│    │     normalized, created_at              │
                                           └─────────┘    └─────────────────────────────────────────┘
                                               │ 1
                                               ▼ N (projects)
                                           ┌─────────────────────────────────────────┐
                                           │            PROJECTION_OUTBOX            │
                                           ├─────────────────────────────────────────┤
                                           │ PK  id                                  │
                                           │ FK  tenant_id, vin, payload, status     │
                                           └─────────────────────────────────────────┘

 Additional Tenant-Scoped Governance:
  • TENANTS (1) ──► (N) REPLAY_JOBS   : Tracks bulk replay execution, recovery & error counts
  • TENANTS (1) ──► (N) AUDIT_LOG     : Immutable actor, action, resource, timestamp record
  • TENANTS (1) ──► (N) ERASURES      : Right-to-be-forgotten / cryptographic erasure hashes
```


Fleet, OEM model and driver attributes are separate entities. Vehicle stores identity and references, avoiding repeated manufacturer/model/powertrain/driver-name attributes. `vehicle_catalog` is a joined view for read queries. `vehicle_state` is deliberately denormalized CQRS state. Telemetry preserves source JSON and mapping revisions; it is not represented as a huge set of relational signal columns. Telemetry, audit and outbox are deliberately not connected by restrictive foreign keys, allowing retention and erasure. Diagram edges include application-enforced relationships, not only SQL foreign keys.

Remaining integrity hardening: composite tenant foreign keys for fleet/driver assignment and row-level security as defense in depth. Current API has no general vehicle enrollment mutation; supplied fixtures and tenant-scoped service queries preserve these ownership rules. Do not expose direct arbitrary registry writes.

## Event semantics

- Identity: `(tenant_id,event_id)` is unique. A duplicate delivery does not duplicate raw evidence, revision, alert episode or outbox projection.
- Ordering: per-vehicle Kafka key is `tenant:VIN`. SQL state compares event time truncated to database microseconds, then sequence number. Old late data is retained but does not replace latest state.
- Contract selection: only approved mappings for an exact OEM/schema version run. Unknown schema is quarantined. Numeric strings are not coerced; absent optional values stay null.
- Quality: missing/invalid coordinates, battery bounds, speed bounds, VIN checksum and DTC parsing are explicit failures. A newer quarantined event keeps state untrusted until valid evidence catches up.
- Durability: normalized state, raw status, revision, alert and projection outbox commit in one transaction. Projection retries back off and use idempotent destination keys.
- Delivery: Kafka adapter is at-least-once. A crash after SQL commit but before offset commit causes redelivery. There is **no global exactly-once guarantee** across Kafka/SQL/Redis/Cassandra.
- Replay: DB row locks and durable cursor/counters commit with each 100-event page. Workers use `SKIP LOCKED`. Process death rolls back its page and another process can continue. Cross-node kill recovery still requires chaos verification.
- Caching: local approved-mapping cache clears immediately on same-process approval and refreshes every five seconds. Other nodes can briefly use a prior approved revision; broadcast invalidation is a production follow-up. Unknown versions remain quarantined.
- Backpressure: Kafka buffers within retention/storage limits; producer ack waits and timeouts propagate 503 for HTTP intake. Client retries must reuse event IDs. The local REST rate guard is not a 100K/s ingress gateway.

## CAP and PACELC

| Data                             | Partition behavior                                                                         | Normal-operation tradeoff                                                                 |
| :------------------------------- | :----------------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------------- |
| Ownership, approvals and erasure | Favor consistency; reject mutations when authoritative DB is unavailable                   | Accept quorum/transaction latency for correct authorization                               |
| Raw Kafka telemetry              | With `acks=all` and min ISR 2/RF3 in production, under-replicated partitions reject writes | Prefer durability over maximum partition availability; upstream must buffer               |
| Current telemetry projections    | May be stale/unavailable while raw log is durable                                          | Prefer low-latency eventual reads; expose freshness and quality                           |
| Cassandra RF3 target             | Read/write consistency is a deployment decision                                            | LOCAL_QUORUM favors stronger local consistency; LOCAL_ONE favors latency and availability |
| Static runbook vectors           | Retrieval can fall back to local authored corpus                                           | Availability is acceptable; never grants data access or approval authority                |

Calling all raw telemetry "AP" would be misleading with all-ISR acknowledgement. Requirements for buffering, quorum and acceptable stale state must be explicit. Compose uses RF1 and does not implement this multi-zone configuration.

## Lifecycle and capacity worksheet

At 100K events/sec and 1,000 bytes/event: 100 MB/sec, 8.64 TB/day in decimal units, before envelopes/indexes/replication. A five-minute 300K/sec burst creates 90M events or 90GB raw. If consumers hold 100K/sec, the extra backlog is 60GB; at 200K/sec after the burst it takes 10 minutes to drain. These are calculations, not benchmark results.

| Tier             | Proposed policy                                               | Implemented behavior                                      |
| :--------------- | :------------------------------------------------------------ | :-------------------------------------------------------- |
| Kafka raw        | 6 hours; RF3, compression, min ISR2                           | Raw topic retention 6 hours; Compose RF1                  |
| Hot cache        | Latest state, 24h TTL, disposable                             | Redis conditional latest timestamp/sequence and TTL       |
| Hot telemetry    | 24h, tenant/VIN/hour buckets                                  | Optional Cassandra table TTL 86400; demo RF1              |
| Warm lake        | Days 1-30, partition by event day and OEM                     | Local partitioned Parquet export; not automated archival  |
| Cold lake        | Day31-365, encrypted object storage                           | Terraform bucket lifecycle template; no deployed pipeline |
| Audit            | Proposed 90-day active retention plus policy-reviewed archive | No automatic audit expiry in native demo                  |
| Local SQL ledger | Bounded demonstration store                                   | No automatic deletion; grows while simulator runs         |

For an assumed 4:1 compression, primary raw storage is about 2.16TB/day before replicas. Approximate monthly storage cost is `retained GB * provider price per GB-month`, plus requests, scan bytes, nodes, network, replicas and backups. Obtain a dated regional quote before assigning currency amounts; none is represented as a current vendor price. Reduce event payloads, aggregate lower-value history and avoid writing every raw event to every index. A cold lifecycle is not a privacy-erasure guarantee: older object versions and backups also need deletion handling.
