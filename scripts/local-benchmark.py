"""Small, bounded read benchmark. Never labels local results as production scale."""
import argparse
import asyncio
import json
import math
import os
from pathlib import Path
import time
from datetime import datetime, timezone
import httpx


async def run(base, token, requests, concurrency):
    durations, errors, by_endpoint = [], [], {}
    semaphore = asyncio.Semaphore(concurrency)
    async with httpx.AsyncClient(base_url=base, headers={"Authorization": "Bearer " + token}, timeout=30) as client:
        snapshot = await client.get('/api/overview')
        snapshot.raise_for_status()
        workload = {key: snapshot.json()[key] for key in ['vehicles', 'processed', 'eventsPerSecond']}
        async def sample(i):
            async with semaphore:
                endpoint = ["/api/overview", "/api/vehicles?limit=25", "/api/alerts"][i % 3]
                start = time.perf_counter()
                try:
                    response = await client.get(endpoint)
                    if response.status_code != 200:
                        errors.append({'endpoint': endpoint, 'status': response.status_code})
                except httpx.RequestError as error:
                    errors.append({'endpoint': endpoint, 'error': type(error).__name__})
                elapsed = (time.perf_counter() - start) * 1000
                durations.append(elapsed)
                by_endpoint.setdefault(endpoint, []).append(elapsed)
        started = time.perf_counter()
        await asyncio.gather(*(sample(i) for i in range(requests)))
    def percentiles(samples):
        samples = sorted(samples)
        return {f'p{p}Ms': samples[max(0, math.ceil(p / 100 * len(samples)) - 1)] for p in [50, 95, 99]}
    return {"recordedAt": datetime.now(timezone.utc).isoformat(), "requests": requests, "concurrency": concurrency, "errors": errors, "elapsedSeconds": time.perf_counter() - started, **percentiles(durations), "endpoints": {key: {'requests': len(value), **percentiles(value)} for key, value in by_endpoint.items()}, "workloadAtStart": workload, "percentileMethod": "nearest-rank", "warmup": "One overview read before timing", "scope": "Local API smoke benchmark; not 100K events/sec, burst, soak, HA, or production SLO proof"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://127.0.0.1:8081")
    parser.add_argument("--requests", type=int, default=90)
    parser.add_argument("--concurrency", type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.requests <= 1000 or not 1 <= args.concurrency <= 20:
        raise SystemExit("Local benchmark is bounded to 1000 requests and 20 clients")
    report = asyncio.run(run(args.api, os.environ["FLEETTRUTH_TOKEN"], args.requests, args.concurrency))
    Path("evidence/local-api-benchmark.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if report['errors']:
        raise SystemExit('Local API benchmark recorded request failures')
