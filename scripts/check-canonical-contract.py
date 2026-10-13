#!/usr/bin/env python3
"""Offline SHA-pinned canonical AgentRunStore and journal compatibility preflight.

This is a structural contract check, NOT a Kotlin compiler or an ABI validator.
It deliberately reports unknown journal variants instead of assuming that
unsupported evidence can be discarded or safely replayed.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
CANONICAL_FILES = (
    "runtime/cognition/src/main/java/com/halo/runtime/cognition/agent/AgentRunStore.kt",
    "runtime/cognition/src/main/java/com/halo/runtime/cognition/agent/AgentRunJournal.kt",
    "runtime/cognition/src/main/java/com/halo/runtime/cognition/agent/AgentRunCoordinator.kt",
)
LINUX_STORE = ROOT / "adapters/agent-store/src/main/java/com/halo/linux/agent/SqliteAgentRunStore.kt"
LINUX_CODEC = ROOT / "adapters/agent-store/src/main/java/com/halo/linux/agent/CanonicalAgentJson.kt"
MANDATORY_METHODS = {
    "observeRuns", "get", "create", "update", "appendStep", "children",
    "createChildIfAllowed", "nonTerminalRuns", "claimResume", "claimRecovery",
    "resolveInputIfPending",
}
CRITICAL_EVIDENCE = {
    "RunPrepared", "ProviderTurnStarted", "ProviderTurnCompleted",
    "ResponseProduced", "ResponseCommitted", "RecoveryClaimed",
    "ToolInvocationPrepared", "ToolExecutionClaimed", "ToolOutcomeRecorded",
    "InputRequested", "InputResolved",
}


def inspect(canonical: Path, *, require_pin: bool = True) -> tuple[int, int]:
    canonical = canonical.resolve(strict=True)
    if require_pin:
        expected = (ROOT / "config/canonical-halo.sha").read_text().strip()
        if not re.fullmatch(r"[0-9a-f]{40}", expected):
            raise ValueError("Invalid canonical source SHA pin")
        actual = subprocess.check_output(
            ["git", "-C", str(canonical), "rev-parse", "HEAD"],
            text=True, stderr=subprocess.DEVNULL,
        ).strip()
        if actual != expected:
            raise ValueError("Canonical checkout differs from pinned source revision")
    sources = [(canonical / path).read_text(encoding="utf-8") for path in CANONICAL_FILES]
    iface, journal, coordinator = sources
    implementation = LINUX_STORE.read_text(encoding="utf-8")
    codec = LINUX_CODEC.read_text(encoding="utf-8")

    declared = set(re.findall(
        r"(?m)^\s*(?:suspend\s+)?fun\s+(\w+)\s*\(", iface
    ))
    implemented = set(re.findall(
        r"(?m)^\s*override\s+(?:suspend\s+)?fun\s+(\w+)\s*\(", implementation
    ))
    missing = MANDATORY_METHODS - declared
    if missing:
        raise ValueError(f"Canonical AgentRunStore contract missing: {sorted(missing)}")
    unimplemented = MANDATORY_METHODS - implemented
    if unimplemented:
        raise ValueError(f"Linux AgentRunStore overrides missing: {sorted(unimplemented)}")

    if "class DefaultAgentRunCoordinator" not in coordinator:
        raise ValueError("Missing canonical DefaultAgentRunCoordinator")
    if "prepareOutstandingRunsForRecovery" not in coordinator:
        raise ValueError("Canonical coordinator recovery entry point changed")

    body = journal.split("sealed interface AgentStep", 1)
    if len(body) != 2:
        raise ValueError("Cannot locate canonical AgentStep variants")
    canonical_steps = set(re.findall(
        r"(?m)^\s*data class\s+([A-Za-z]\w*)\s*\(", body[1]
    ))
    if not canonical_steps:
        raise ValueError("Canonical AgentStep variants not found")
    encoded = set(re.findall(r"\bis\s+AgentStep\.(\w+)\s*->", codec))
    decode_body = codec.split("fun decodeStep(", 1)
    if len(decode_body) != 2:
        raise ValueError("Linux canonical journal decoder not found")
    decode_body = decode_body[1].split("private fun encodeInput(", 1)[0]
    decoded = set(re.findall(r'(?m)^\s*"([A-Za-z]\w*)"\s*->', decode_body))
    # Restrict to known AgentStep names, not private input subtypes.
    decoded &= canonical_steps
    missing_decode = encoded - decoded
    missing_encode = decoded - encoded
    if missing_decode or missing_encode:
        raise ValueError(
            f"Lossy journal codec: encode-only={sorted(missing_decode)}, "
            f"decode-only={sorted(missing_encode)}"
        )
    unknown = encoded - canonical_steps
    if unknown:
        raise ValueError(f"Linux encodes steps absent from canonical Halo: {sorted(unknown)}")
    unsupported_critical = CRITICAL_EVIDENCE - encoded
    if unsupported_critical:
        raise ValueError(f"Critical recovery evidence unsupported: {sorted(unsupported_critical)}")
    if "UnsupportedOperationException" not in codec:
        raise ValueError("Unknown journal variants must fail closed")

    unsupported = sorted(canonical_steps - encoded)
    print(f"PASS: {len(MANDATORY_METHODS)} canonical store methods overridden")
    print(f"PASS: {len(encoded)} canonical journal variants encode and decode symmetrically")
    if unsupported:
        print(f"EXPLICITLY UNSUPPORTED: {', '.join(unsupported)}")
    print("NOTE: source-level contract check only; run Gradle/JUnit separately")
    return len(encoded), len(unsupported)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("canonical_root", type=Path)
    args = parser.parse_args()
    try:
        inspect(args.canonical_root)
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"FAIL: canonical source contract: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
