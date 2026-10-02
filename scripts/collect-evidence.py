import csv
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[1]
critical_classes = {
    "io.fleettruth.domain.Normalizer",
    "io.fleettruth.domain.VinValidator",
    "io.fleettruth.service.FleetService",
    "io.fleettruth.service.ReplayWorker",
    "io.fleettruth.service.PrivacyService",
    "io.fleettruth.service.AssistantService",
    "io.fleettruth.service.DriftModel",
    "io.fleettruth.service.TelemetryRollups",
    "io.fleettruth.service.ProjectionCircuits",
    "io.fleettruth.service.StorageProjection",
}
totals = {key: 0 for key in ["tests", "failures", "errors", "skipped"]}
reports = list((root / "api/target/surefire-reports").glob("TEST-*.xml"))
reports += list((root / "api/target/failsafe-reports").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("No backend test reports found; run mvn verify first")
suites = []
for path in reports:
    suite = ET.parse(path).getroot()
    suites.append({"name": suite.get("name"), "report": str(path.relative_to(root)), **{key: int(suite.get(key, 0)) for key in totals}})
    for key in totals:
        totals[key] += int(suite.get(key, 0))
coverage = []
with (root / "api/target/site/jacoco/jacoco.csv").open() as file:
    for row in csv.DictReader(file):
        entry = {"class": row["PACKAGE"] + "." + row["CLASS"]}
        for counter in ["INSTRUCTION", "LINE", "BRANCH"]:
            covered, missed = int(row[counter + "_COVERED"]), int(row[counter + "_MISSED"])
            entry[counter.lower() + "Percent"] = round(100 * covered / (covered + missed), 2) if covered + missed else None
        coverage.append(entry)
critical = [entry for entry in coverage if entry["class"] in critical_classes]
missing = sorted(critical_classes - {entry["class"] for entry in critical})
below_threshold = [entry["class"] for entry in critical if any(entry[counter] is None or entry[counter] < 80 for counter in ["instructionPercent", "linePercent"])]
gate = {"minimumPercent": 80, "counters": ["instructionPercent", "linePercent"], "classes": sorted(critical_classes), "missing": missing, "belowThreshold": below_threshold, "passed": not missing and not below_threshold}
totals["passed"] = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
report = {"recordedAt":datetime.now(timezone.utc).isoformat(), "backend":totals, "suites":suites, "criticalCoverageGate":gate, "coverage":coverage, "containerTests":"See individual PostgreSQL and Cassandra suites; skipped tests are not passes", "scaleTargets":"100K/s sustained, 300K/s burst and billion-row historical runs have not been validated locally"}
(root / "evidence/verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(totals))
print(json.dumps(gate))
if not gate["passed"] or totals["failures"] or totals["errors"]:
    raise SystemExit("Backend tests or critical-code coverage gate failed")
