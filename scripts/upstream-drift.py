#!/usr/bin/env python3
"""Read-only divergence report for temporarily copied portable Halo source.

Usage:
  python3 scripts/upstream-drift.py --halo-repo ../Halo
  python3 scripts/upstream-drift.py --halo-repo ../Halo --strict

Does not fetch, modify, or publish either repository.
"""
import argparse
import hashlib
import subprocess
from pathlib import Path

MODULES = (
    "sdk/runtime-sdk",
    "sdk/provider-sdk",
    "sdk/host-sdk",
    "libraries/common",
    "runtime/events",
)

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--halo-repo", type=Path, default=Path("../Halo"))
    parser.add_argument("--strict", action="store_true",
                        help="Fail if files differ, are missing, or exist only in one tree")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    upstream = args.halo_repo.resolve()
    if not (upstream / "settings.gradle.kts").is_file():
        parser.error(f"Not a checked-out Halo repository: {upstream}")
    try:
        sha = subprocess.check_output(
            ["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True
        ).strip()
    except (FileNotFoundError, subprocess.CalledProcessError):
        sha = "unknown"
    print(f"Canonical Halo: {upstream} at {sha}")
    summary = {"same": 0, "changed": 0, "local_only": 0, "upstream_only": 0}
    for module in MODULES:
        local = root / module
        origin = upstream / module
        relative = lambda base: {
            p.relative_to(base) for p in base.rglob("*.kt")
        } if base.is_dir() else set()
        local_files = relative(local)
        upstream_files = relative(origin)
        for name in sorted(local_files | upstream_files):
            path = f"{module}/{name}"
            if name not in local_files:
                kind = "upstream_only"
            elif name not in upstream_files:
                kind = "local_only"
            else:
                kind = "same" if sha256(local / name) == sha256(origin / name) else "changed"
            summary[kind] += 1
            if kind != "same":
                print(f"{kind}: {path}")
    print("Source drift:", ", ".join(f"{k}={v}" for k,v in summary.items()))
    print("This comparison is informational; matching source does not prove binary compatibility.")
    return 1 if args.strict and any(summary[k] for k in ("changed", "local_only", "upstream_only")) else 0

if __name__ == "__main__":
    raise SystemExit(main())
