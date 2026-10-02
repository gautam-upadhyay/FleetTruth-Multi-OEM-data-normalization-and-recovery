"""Bounded HTTP security regression checks against a local FleetTruth demo."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.parse import urlparse
import httpx


def run(base):
    if urlparse(base).hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise SystemExit("This check is restricted to the local demo")
    checks = []

    def check(name, passed, observed):
        checks.append({"name": name, "passed": bool(passed), "observed": observed})

    with httpx.Client(base_url=base, timeout=30) as client:
        def login(email):
            response = client.post("/api/auth/login", json={"email": email, "password": "FleetTruth2026!"})
            response.raise_for_status()
            return {"Authorization": "Bearer " + response.json()["token"]}

        engineer = login("engineer@fleettruth.demo")
        viewer = login("viewer@fleettruth.demo")
        other = login("other@fleettruth.demo")
        for name, path, headers, expected in [
            ("Anonymous overview denied", "/api/overview", {}, 401),
            ("Malformed JWT denied", "/api/overview", {"Authorization": "Bearer malformed"}, 401),
            ("Anonymous metrics denied", "/actuator/prometheus", {}, 401),
            ("Viewer metrics denied", "/actuator/prometheus", viewer, 403),
            ("Viewer raw evidence denied", "/api/events/private-id", viewer, 403),
            ("Authenticated identity available", "/api/auth/me", engineer, 200),
        ]:
            response = client.get(path, headers=headers)
            check(name, response.status_code == expected, response.status_code)
        response = client.post("/api/replays", json={"oem": "helix"}, headers=viewer)
        check("Viewer replay denied", response.status_code == 403, response.status_code)
        response = client.post("/api/privacy/erase", json={"vin": "invalid", "confirmation": "invalid"}, headers=engineer)
        check("Engineer erasure denied", response.status_code == 403, response.status_code)
        response = client.get("/api/vehicles?limit=1", headers=engineer)
        response.raise_for_status()
        vin = response.json()["items"][0]["vin"]
        response = client.get("/api/vehicles/" + vin, headers=other)
        check("Cross-tenant vehicle concealed", response.status_code == 404, response.status_code)
        response = client.get("/api/vehicles", params={"search": "' OR 1=1 --"}, headers=engineer)
        check("Search treats SQL syntax as data", response.status_code == 200 and response.json().get("total") == 0, response.status_code)
        response = client.get("/api/overview", headers={**engineer, "Origin": "https://untrusted.invalid"})
        check("Cross-origin access is not granted", "access-control-allow-origin" not in response.headers, response.headers.get("access-control-allow-origin"))
        check("MIME sniffing disabled", response.headers.get("x-content-type-options") == "nosniff", response.headers.get("x-content-type-options"))
        check("API framing disabled", "frame-ancestors 'none'" in response.headers.get("content-security-policy", ""), response.headers.get("content-security-policy"))
    return {"recordedAt": datetime.now(timezone.utc).isoformat(), "target": base,
            "scope": "13 bounded authenticated HTTP regression checks; not a comprehensive DAST scan or penetration test",
            "checks": checks, "passed": all(check["passed"] for check in checks)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://127.0.0.1:8081")
    parser.add_argument("--output", default="evidence/security-smoke.json")
    args = parser.parse_args()
    report = run(args.api)
    root = Path(__file__).resolve().parents[1]
    (root / args.output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not report["passed"]:
        raise SystemExit("A security regression check failed")
