#!/usr/bin/env python3
"""Check the portable Halo snapshot; explicit --apply --revision SHA updates it.

No network access, branch changes, Android edits, builds or automatic commits.
"""
import argparse
from pathlib import Path
import subprocess
import sys

import canonical_sources as sources


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--halo-repo", type=Path, help="Clean canonical checkout at the pinned/selected revision")
    parser.add_argument("--apply", action="store_true", help="Update the reviewed source snapshot")
    parser.add_argument("--revision", help="Full reviewed SHA; mandatory for --apply")
    parser.add_argument("--include-cognition", action="store_true",
                        help="Also verify the external canonical cognition sources (read-only)")
    args = parser.parse_args()
    if args.apply and (args.halo_repo is None or args.revision is None):
        parser.error("--apply requires --halo-repo and an explicit --revision SHA")
    if args.revision is not None and not args.apply:
        parser.error("--revision is only for --apply; use upstream-drift.py to inspect a candidate revision")
    if args.include_cognition and (args.apply or args.halo_repo is None):
        parser.error("--include-cognition requires --halo-repo and cannot be used with --apply")
    root = Path(__file__).resolve().parent.parent
    try:
        if args.apply:
            count = sources.synchronize(root, args.halo_repo, args.revision)
            print(f"Synchronized {count} portable sources from canonical Halo {args.revision}")
        drift = sources.verify(root, args.halo_repo, include_cognition=args.include_cognition)
        if drift:
            print("FAIL: canonical source drift:\n" + "\n".join(drift), file=sys.stderr)
            return 1
        print(f"PASS: canonical source snapshot at {sources.revision(root)}")
        print("Source integrity only; run Gradle and host acceptance separately.")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"FAIL: canonical sources: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
