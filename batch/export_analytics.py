"""Bounded historical export -> partitioned Parquet -> DuckDB quality report."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import duckdb
import httpx
import pyarrow as pa
import pyarrow.dataset as ds


def flatten(row):
    normalized = row.get("normalized")
    value = json.loads(normalized) if isinstance(normalized, str) else normalized or {}
    return {"id": row["id"], "vin": row["vin"], "oem": row["oemId"], "event_time": row["eventTime"], "day": row["eventTime"][:10], "status": row["status"], "soc_pct": value.get("socPct"), "speed_kmh": value.get("speedKmh"), "mapping_id": row.get("mappingId")}


def export(base, token, root, maximum=100000):
    before = datetime.now(timezone.utc).isoformat()
    folder = Path(root) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    folder.mkdir(parents=True, exist_ok=False)
    schema = pa.schema([(name, pa.string()) for name in ["id", "vin", "oem", "event_time", "day", "status"]] + [("soc_pct", pa.float64()), ("speed_kmh", pa.float64()), ("mapping_id", pa.string())])
    cursor, total, pages = "", 0, 0
    with httpx.Client(base_url=base, headers={"Authorization": "Bearer " + token}, timeout=60) as client:
        while total < maximum:
            limit = min(5000, maximum - total)
            response = client.get("/api/export", params={"cursor": cursor, "limit": limit, "before": before})
            response.raise_for_status()
            rows = [json.loads(line) for line in response.text.splitlines() if line.strip()]
            if not rows:
                break
            table = pa.Table.from_pylist([flatten(row) for row in rows], schema=schema)
            ds.write_dataset(table, folder, format="parquet", partitioning=["day", "oem"], partitioning_flavor="hive", basename_template=f"part-{pages}-{{i}}.parquet", existing_data_behavior="overwrite_or_ignore")
            cursor, total, pages = rows[-1]["id"], total + len(rows), pages + 1
            if len(rows) < limit:
                break
    if not total:
        raise ValueError("No events were available for export")
    with duckdb.connect() as db:
        db.read_parquet(str(folder / "**/*.parquet"), hive_partitioning=True).create_view("events")
        result = db.execute("SELECT CAST(day AS VARCHAR),oem,COUNT(*),SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),AVG(soc_pct),AVG(speed_kmh) FROM events GROUP BY day,oem ORDER BY day,oem").fetchall()
    report = {"createdAt": before, "rows": total, "pages": pages, "limitReached": total == maximum, "parquetDirectory": str(folder.resolve()), "aggregates": [dict(zip(["day", "oem", "events", "accepted", "meanSoc", "meanSpeed"], row)) for row in result], "scope": "Local bounded batch, not a billion-row benchmark. Source statuses may change during concurrent replay."}
    (folder / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://127.0.0.1:8081")
    parser.add_argument("--out", default=".runtime/lake")
    parser.add_argument("--maximum", type=int, default=100000)
    args = parser.parse_args()
    token = os.environ.get("FLEETTRUTH_TOKEN")
    if not token:
        raise SystemExit("Set FLEETTRUTH_TOKEN to an engineer/admin access token")
    print(json.dumps(export(args.api, token, args.out, args.maximum), indent=2))
