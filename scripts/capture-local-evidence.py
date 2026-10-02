"""Capture sanitized health, metrics, trace and image-scan summaries for the local demo."""
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import httpx

ROOT = Path(__file__).resolve().parents[1]


def get(url, **kwargs):
    response = httpx.get(url, timeout=30, **kwargs)
    response.raise_for_status()
    return response.json()


def main():
    services = [json.loads(line) for line in subprocess.check_output(
        ["docker", "compose", "ps", "--format", "json"], cwd=ROOT, text=True).splitlines() if line.strip()]
    login = httpx.post("http://127.0.0.1:8082/api/auth/login", json={
        "email": "engineer@fleettruth.demo", "password": "FleetTruth2026!"}, timeout=30)
    login.raise_for_status()
    overview = get("http://127.0.0.1:8082/api/overview", headers={"Authorization": "Bearer " + login.json()["token"]})
    targets = get("http://127.0.0.1:9090/api/v1/targets")["data"]["activeTargets"]
    trace_services = get("http://127.0.0.1:16686/api/v3/services")["services"]
    operations = get("http://127.0.0.1:16686/api/v3/operations", params={"service": "fleettruth"}).get("operations", [])
    now = datetime.now(timezone.utc)
    trace_response = httpx.get("http://127.0.0.1:16686/api/v3/traces", params={
        "query.serviceName": "fleettruth", "query.startTimeMin": (now - timedelta(minutes=10)).isoformat(),
        "query.startTimeMax": now.isoformat(), "query.searchDepth": 30}, timeout=30)
    trace_response.raise_for_status()
    spans = []
    for line in trace_response.text.splitlines():
        envelope = json.loads(line)
        for resource in envelope.get("result", envelope).get("resourceSpans", []):
            for scope in resource.get("scopeSpans", []):
                spans.extend(scope.get("spans", []))
    scans = {}
    java_report = None
    for label in ["api", "web"]:
        scan = json.loads((ROOT / f"evidence/{label}-image-audit.json").read_text(encoding="utf-8-sig"))
        if label == "api":
            java_results = [result for result in scan.get("Results", []) if result.get("Type") == "jar"]
            assert java_results and all(result.get("Packages") for result in java_results), "Java scan must identify packages"
            java_report = {"recordedAt": scan["CreatedAt"], "source": "api-image-audit.json",
                "imageId": scan.get("Metadata", {}).get("ImageID"), "results": java_results,
                "scope": "Java package inventory and findings extracted from the actual Trivy application-image scan"}
        scans[label] = {"createdAt": scan["CreatedAt"], "imageId": scan.get("Metadata", {}).get("ImageID"),
            "targets": [{"type": result["Type"], "packages": len(result.get("Packages", [])),
                         "findings": dict(Counter(v["Severity"] for v in result.get("Vulnerabilities", [])))}
                        for result in scan.get("Results", [])]}
    report = {"recordedAt": now.isoformat(),
        "compose": [{key: service.get(key) for key in ["Service", "State", "Health", "Image", "Ports"]} for service in services],
        "dockerOverview": {key: overview[key] for key in ["vehicles", "processed", "accepted", "quarantined", "eventsPerSecond"]},
        "prometheus": [{key: target.get(key) for key in ["health", "lastError", "lastScrape", "scrapeUrl"]} for target in targets],
        "grafana": get("http://127.0.0.1:3001/api/health"),
        "tracing": {"services": trace_services, "operations": operations, "sampledSpans": len(spans),
                    "sampledTraceIds": len({span["traceId"] for span in spans}),
                    "spanNames": sorted({span["name"] for span in spans})},
        "imageScans": scans,
        "scope": "Local single-node synthetic demo. Jaeger uses in-memory storage. Application image scans exclude infrastructure images; no HA, fleet-scale or centralized-log-store proof."}
    (ROOT / "evidence/local-stack.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (ROOT / "evidence/java-dependency-audit.json").write_text(json.dumps(java_report, indent=2) + "\n", encoding="utf-8")
    assert overview["vehicles"] == 100000
    assert targets and all(target["health"] == "up" for target in targets)
    assert "fleettruth" in trace_services and spans
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
