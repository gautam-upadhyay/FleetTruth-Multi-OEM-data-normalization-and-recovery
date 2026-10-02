"""Package source, presentation material and selected evidence with a SHA-256 manifest."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts/submission"
EXCLUDED = {".git", ".env", ".runtime", ".cache", "node_modules", "target", "dist",
            "__pycache__", ".pytest_cache", ".terraform", "test-results", "playwright-report", "raw"}
SUFFIXES = {".pem", ".key", ".log", ".tfstate", ".pyc", ".tsbuildinfo"}


def eligible(path):
    parts = path.relative_to(ROOT).parts
    return (path.is_file() and not path.is_symlink() and not (set(parts) & EXCLUDED)
            and path.suffix not in SUFFIXES and not path.name.startswith(".env")
            and ".tfstate" not in path.name)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    roots = [ROOT / name for name in ["api", "web", "docs", "infra", "scripts", "tests", "ml", "batch", "evidence", "artifacts/demo", ".github"]]
    files = set()
    for folder in roots:
        for parent, directories, names in os.walk(folder, followlinks=False):
            directories[:] = [name for name in directories if name not in EXCLUDED and not (Path(parent) / name).is_symlink()]
            files.update(path for name in names if eligible(path := Path(parent) / name))
    files.update(path for path in ROOT.iterdir() if eligible(path))
    # Test XML and browser traces can contain request tokens, so only sanitized summaries are shipped.
    reports = ROOT / "api/target/site/jacoco"
    files.update(path for path in reports.rglob("*") if path.is_file() and not path.is_symlink())
    screenshots = ROOT / "web/test-results/screenshots"
    files.update(screenshots.glob("*.png"))
    manifest = {str(path.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sorted(files)}
    archive = OUTPUT / "FleetTruth-submission.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as package:
        for path in sorted(files):
            package.write(path, path.relative_to(ROOT).as_posix())
        package.writestr("SHA256-MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
    with ZipFile(archive) as package:
        assert package.testzip() is None, "Archive integrity check failed"
        for name, expected in manifest.items():
            assert hashlib.sha256(package.read(name)).hexdigest() == expected, name
        assert not any(".env" == part or part in {".runtime", "node_modules"} for name in package.namelist() for part in Path(name).parts)
    summary = {"recordedAt": datetime.now(timezone.utc).isoformat(), "archive": archive.name,
               "files": len(manifest), "bytes": archive.stat().st_size,
               "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
               "scope": "Source and local evidence; credentials, dependency caches, runtime databases, test XML and browser traces excluded"}
    (OUTPUT / "package.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
