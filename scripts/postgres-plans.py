"""Run an isolated 100K-row PostgreSQL index experiment inside the local Compose database."""
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SQL = """
CREATE TEMP TABLE plan_events AS SELECT n AS id, (n % 100)::int AS tenant,
  now() - n * interval '1 second' AS received_at, 'ACCEPTED'::text AS status
  FROM generate_series(1,100000) n;
CREATE TEMP TABLE plan_results(stage text,plan jsonb,ids jsonb);
ANALYZE plan_events;
DO $$ DECLARE p json; i json; BEGIN
  EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id,received_at,status FROM plan_events WHERE tenant=42 AND id>50000 ORDER BY id LIMIT 50' INTO p;
  SELECT json_agg(id) INTO i FROM (SELECT id FROM plan_events WHERE tenant=42 AND id>50000 ORDER BY id LIMIT 50) s;
  INSERT INTO plan_results VALUES('before',p,i);
END $$;
CREATE INDEX ON plan_events(tenant,id) INCLUDE(received_at,status);
ANALYZE plan_events;
DO $$ DECLARE p json; i json; BEGIN
  EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id,received_at,status FROM plan_events WHERE tenant=42 AND id>50000 ORDER BY id LIMIT 50' INTO p;
  SELECT json_agg(id) INTO i FROM (SELECT id FROM plan_events WHERE tenant=42 AND id>50000 ORDER BY id LIMIT 50) s;
  INSERT INTO plan_results VALUES('after',p,i);
END $$;
SELECT json_object_agg(stage,json_build_object('plan',plan,'ids',ids)) FROM plan_results;
"""

result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "fleettruth", "-d", "fleettruth", "-qAt", "-v", "ON_ERROR_STOP=1"],
                        cwd=ROOT, input=SQL, text=True, capture_output=True, check=True)
plans = json.loads(result.stdout)
assert plans["before"]["ids"] == plans["after"]["ids"], "Index changed ordered query results"
report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "rows": 100000,
          "sameOrderedResults": True, **plans,
          "scope": "Disposable PostgreSQL keyset/index microbenchmark; temporary tables only; not a production workload or API latency claim"}
(ROOT / "evidence/sql-postgres-query-plans.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"rows": 100000, "sameOrderedResults": True,
                 "beforeMs": plans["before"]["plan"][0]["Execution Time"], "afterMs": plans["after"]["plan"][0]["Execution Time"]}))
