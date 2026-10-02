# Algorithms and SQL

## Algorithms

| Operation | Data structure / rule | Time and space |
| --- | --- | --- |
| VIN validation | 17-position transliteration/weight checksum, reject I/O/Q | O(17) time, O(1) space; strict checksum policy is appropriate for supplied synthetic VINs, not every worldwide VIN regime |
| Signal mapping | JSON pointers plus finite affine transforms; required canonical keys and uniqueness | O(r*d + c) time for r rules, pointer depth d and c DTC codes; O(r+c) output |
| Duplicate delivery | Composite tenant/event primary key | Expected indexed O(log N) lookup/insertion; durable, no Bloom-filter false-positive data loss |
| Latest state | Event-time microseconds then sequence tie-break under vehicle row lock | O(1) comparison plus indexed row access |
| Replay | Ordered ID cursor, page size 100, row-lock checkpoint | O(N log N) indexed traversal upper bound; bounded O(100) application memory per page |
| Time chart | Transactional minute summaries, then 61 fixed buckets including the current partial minute | Indexed bounded summary-range read, O(log B + K) for B stored summary rows and K matching rows; O(61) chart memory. Ingestion pays three summary updates per event; production needs sharded stream aggregation |
| Drift model | Standardize five features; logistic dot product | O(5) inference; bounded feature extraction over <=200 rows/OEM |
| Runbook retrieval | 128-dimensional normalized hashed token vectors, exact cosine | O(D*128) query over D documents; D=4 now, so HNSW adds unjustified complexity |
| Vehicle pagination | Tenant/VIN keyset cursor | O(log V + page size) when index/selectivity allows; no growing OFFSET skip cost |

Graph shortest paths and dynamic programming are not relevant to this normalization project and were not inserted as decorative features. Routing/charging would warrant those algorithms in a different problem scope.

## Query design

The ownership catalog separates vehicle, model, driver and fleet data. List/detail queries join the catalog explicitly, so fetching 25 vehicles does not trigger 25 ORM detail fetches. SQL is parameterized. Tenant filters appear on every user data lookup. VIN keyset pagination uses `(tenant_id,vin)`; filtering by arbitrary substring uses LIKE and is not a general indexed search solution.

Indexes support tenant/latest-event reads, vehicle history, quarantine by tenant/OEM/status/time, alert status/time, replay/outbox jobs, audit history and tenant/OEM/recent drift samples. Overview and OEM event counts read TOTAL summaries; charts use MINUTE summaries and analytics uses DAY summaries. V8 backfills these rows once, then ingestion/replay/erasure update them transactionally. At 100K/s the shared summary rows become write contention points; replace them with sharded stream-maintained counters and independently served projections. Registry counts still query relational ownership/model data.

Vehicle pagination first selects a page from the ownership table, then joins model, driver and latest state. Empty search terms do not add `LIKE '%%'`. The inner ordering includes `tenant_id,vin` to match the composite index, including in H2; ordering only by VIN left an avoidable full-catalog sort in its plan. Because tenant is fixed by the predicate, returned VIN ordering is unchanged. Arbitrary substring searches can still scan the tenant catalog.

## Reproducible before/after plans

`QueryPlanTest` executes both the former catalog-join query and current page-first query over 100K synthetic vehicles using H2. It asserts identical ordered results and captures actual `EXPLAIN ANALYZE` output in `evidence/sql-h2-query-plans.json`. The latest clean-test observation was about 571.64 ms before and 4.96 ms after for the instrumented queries. This is a single in-memory plan microbenchmark, not a production percentile or PostgreSQL result.

For the executed PostgreSQL index experiment against local Compose:

```powershell
python scripts/postgres-plans.py
```

The script runs through `docker compose exec`, creates connection-local temporary tables with 100K synthetic rows, collects `EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)` before/after a composite covering index, and writes `evidence/sql-postgres-query-plans.json`. It neither drops application indexes nor mutates existing application tables. The recorded PostgreSQL execution time was 3.111 ms before and 0.063 ms after, with identical ordered 50-row results. A separate `scripts/query-plans.py` helper accepts `PLAN_DATABASE_URL` for an explicitly configured database; that helper was not used for this result.

This microbenchmark is not the PDF's requested proof for the **slowest real queries**. On staging, capture query statistics and explain the actual fleet list, quarantine replay, overview and daily rollup using realistic tenant skew, cold/warm cache and concurrent ingestion. Add partial quarantine/open-alert indexes and materialized rollups only after comparing planning time, execution time, shared buffers, rows scanned, index bytes and write amplification. Keep both plans and workload seeds.

## Known scaling boundary

The prototype transaction performs raw insert, mapping lookup/cache, state update, revision insert, possible alert/incident work and outbox insert. This is excellent for a small auditable reference workflow and unsuitable evidence for 100K/s on one SQL primary. Split bulk events into log/time-series/lake storage, batch sink writes, shard by stable tenant/VIN hash, and retain relational transactions for decisions/control metadata. Query tuning cannot substitute for that architectural change.
