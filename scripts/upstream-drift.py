#!/usr/bin/env python3
"""Compare reviewed production sources with a clean local Android checkout.

The candidate may be newer than the pin. This command never fetches or writes.
Use --strict to reject source drift or a different candidate revision.
"""
import argparse
import subprocess
from pathlib import Path
import sys

import canonical_sources as sources


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--halo-repo", type=Path, default=Path("../Halo"))
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    try:
        upstream = sources.safe_root(args.halo_repo)
        allowed = sources.modules(root)
        pin = sources.revision(root)
        sha = sources.git(upstream, "rev-parse", "HEAD").decode().strip()
        local_integrity = sources.verify(root)
        candidate = sources.hashes(sources.upstream_snapshot(upstream, sha, allowed))
        local = sources.hashes(sources.source_files(root, allowed))
        drift = sources.compare(local, candidate)
        print(f"Pinned Halo: {pin}")
        print(f"Candidate Halo: {sha}")
        for message in local_integrity:
            print(f"Local snapshot integrity: {message}")
        for message in drift:
            print(message)
        same = sum(local.get(path) == digest for path, digest in candidate.items())
        print(f"Production source comparison: {same} identical, {len(drift)} differences")
        print("Tests and build files are outside source ownership; matching bytes do not prove ABI compatibility.")
        return 1 if args.strict and (drift or local_integrity or pin != sha) else 0
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"FAIL: canonical source comparison: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
