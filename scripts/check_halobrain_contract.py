#!/usr/bin/env python3
"""Detect changes to the reviewed Android archive contract. Offline and read-only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

CONTRACT = Path(__file__).resolve().parents[1] / "config/halobrain-contract.json"


def check_contract(root: Path, contract: dict) -> dict[str, str]:
    """Compare actual working-tree bytes, not merely a checkout's commit label."""
    results = {}
    for relative, expected in contract["sources"].items():
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or not re.fullmatch(r"[0-9a-f]{40}", expected):
            raise ValueError("Invalid compatibility manifest")
        source = root / path
        try:
            info = source.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_size > 2 * 1024**2:
                results[relative] = "unsupported-file"
                continue
            # The file-size check is repeated after read to bound concurrent growth.
            fd = os.open(source, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as stream:
                opened = os.fstat(stream.fileno())
                if not stat.S_ISREG(opened.st_mode) or opened.st_size > 2 * 1024**2:
                    results[relative] = "unsupported-file"
                    continue
                data = stream.read(2 * 1024**2 + 1)
            if len(data) > 2 * 1024**2:
                results[relative] = "unsupported-file"
                continue
            actual = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
            results[relative] = "match" if actual == expected else "changed"
        except OSError:
            results[relative] = "missing-or-unreadable"
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("android_root", type=Path, help="Existing local Halo Android checkout; no fetch or modification is performed")
    args = parser.parse_args()
    try:
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        results = check_contract(args.android_root, contract)
        for path, state in results.items():
            print(f"{state}: {path}")
        matched = bool(results) and all(state == "match" for state in results.values())
        print("Archive contract matches reviewed source bytes." if matched else "Archive contract drift: review before updating the compatibility pin.")
        print("This check does not verify Android/Linux runtime parity or record import.")
        return 0 if matched else 2
    except (OSError, ValueError, KeyError, TypeError):
        print("Unable to inspect the archive compatibility manifest.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
