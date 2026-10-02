"""Compare a disposable PostgreSQL keyset workload before/after its covering index."""
import json
import os
from pathlib import Path
import psycopg

with psycopg.connect(os.environ["PLAN_DATABASE_URL"]) as db:
    with db.cursor() as cur:
        cur.execute("CREATE TEMP TABLE plan_events AS SELECT n AS id, (n % 100)::int AS tenant, now() - n * interval '1 second' AS received_at, 'ACCEPTED'::text AS status FROM generate_series(1,100000) n")
        cur.execute("ANALYZE plan_events")
        query = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id,received_at,status FROM plan_events WHERE tenant=42 AND id>50000 ORDER BY id LIMIT 50"
        cur.execute(query)
        before = cur.fetchone()[0]
        cur.execute("CREATE INDEX ON plan_events(tenant,id) INCLUDE(received_at,status)")
        cur.execute("ANALYZE plan_events")
        cur.execute(query)
        after = cur.fetchone()[0]
report = {"rows":100000, "before":before, "after":after, "scope":"Disposable relational microbenchmark, not the full production query distribution"}
Path("evidence/sql-query-plans.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
