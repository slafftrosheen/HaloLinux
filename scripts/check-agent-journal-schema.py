#!/usr/bin/env python3
"""Execute the canonical SQLite journal DDL from Kotlin without Gradle.

This is a real SQLite constraint test (using the local Python sqlite3 build),
NOT a substitute for JDBC/Kotlin/ARM64 acceptance.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "adapters/agent-store/src/main/java/com/halo/linux/agent/SqliteAgentRunStore.kt"


def statements(source: str) -> list[str]:
    ddl = [
        textwrap.dedent(body).strip()
        for body in re.findall(
            r'stmt\.execute\(\s*"""(.*?)"""\.trimIndent\(\)\)',
            source, flags=re.S,
        )
        if textwrap.dedent(body).strip().startswith("CREATE ")
    ]
    # agent_schema is declared as a plain one-line execute, unlike the
    # other tables/indexes. Keep the exact DDL in sync with Kotlin.
    header = re.search(
        r'stmt\.execute\("((?:CREATE TABLE IF NOT EXISTS agent_schema)[^"]+)"\)',
        source,
    )
    if not header:
        raise AssertionError("Missing canonical agent_schema DDL")
    ddl.insert(0, header.group(1))
    required = (
        "agent_runs", "agent_steps", "agent_schema", "agent_input_requested_unique",
        "agent_input_resolution_unique", "agent_journal_identity_unique",
        "agent_execution_claim_key_unique", "agent_recovery_claim_unique",
    )
    for name in required:
        if not any(re.search(r"\b" + re.escape(name) + r"\b", stmt) for stmt in ddl):
            raise AssertionError("Missing expected agent schema element: " + name)
    return ddl


def exercise(source: str) -> None:
    with tempfile.TemporaryDirectory() as directory:
        location = Path(directory) / "agent.sqlite"
        conn = sqlite3.connect(location, isolation_level=None)
        try:
            conn.execute("PRAGMA foreign_keys=ON")
            for statement in statements(source):
                conn.execute(statement)
            conn.execute(
                "INSERT INTO agent_runs(id,interaction_id,depth,state,updated_at,payload_json)"
                " VALUES ('run-1','turn-1',0,'SUSPENDED','2026-10-12','{}')"
            )
            conn.execute(
                "INSERT INTO agent_runs(id,interaction_id,depth,state,updated_at,payload_json)"
                " VALUES ('run-2','turn-2',0,'SUSPENDED','2026-10-12','{}')"
            )

            def append(
                id: str, run: str = "run-1", kind: str = "ToolExecutionClaimed",
                ordinal: int = 1, *, key: str | None = None,
                journal: str | None = None, fingerprint: str | None = None,
                input_id: str | None = None, resolved: str | None = None,
            ) -> int:
                import json
                payload = json.dumps({"idempotencyKey": key})
                cursor = conn.execute(
                    """INSERT OR IGNORE INTO agent_steps
                    (id,run_id,ordinal,type,schema_version,payload_json,
                    input_request_id,resolution_request_id,recovery_fingerprint,journal_key)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (id,run,ordinal,kind,1,payload,input_id,resolved,fingerprint,journal),
                )
                return cursor.rowcount

            assert append("claim-a", key="unique-action") == 1
            # Execution keys apply globally, including across distinct runs.
            assert append("claim-b", run="run-2", key="unique-action") == 0
            assert append("duplicate-id", kind="ResponseProduced", ordinal=2, journal="response:A") == 1
            assert append("duplicate-key", kind="ResponseProduced", ordinal=3, journal="response:A") == 0
            assert append("recovery-one", kind="RecoveryClaimed", ordinal=3, fingerprint="plan:123") == 1
            assert append("recovery-two", kind="RecoveryClaimed", ordinal=4, fingerprint="plan:123") == 0
            assert append("input-one", kind="InputRequested", ordinal=4, input_id="approval-1") == 1
            assert append("input-two", run="run-2", kind="InputRequested", ordinal=1, input_id="approval-1") == 0
            assert append("resolution-one", kind="InputResolved", ordinal=5, resolved="approval-1") == 1
            assert append("resolution-two", run="run-2", kind="InputResolved", ordinal=1, resolved="approval-1") == 0

            try:
                append("orphan", run="missing-run", ordinal=11, key="orphan-key")
            except sqlite3.IntegrityError:
                pass
            else:
                raise AssertionError("Foreign-key violation was accepted")
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("UPDATE agent_runs SET state='EXECUTING' WHERE id='run-1'")
            assert append("recovery-three", kind="RecoveryClaimed", ordinal=6, fingerprint="plan:123") == 0
            conn.execute("ROLLBACK")
            assert conn.execute("SELECT state FROM agent_runs WHERE id='run-1'").fetchone()[0] == "SUSPENDED"

            # SQLite VACUUM INTO must create a consistent snapshot even after a
            # caller reserves an empty, private destination inode.
            backup = Path(directory) / "reserved-snapshot.sqlite"
            backup.touch(mode=0o600)
            conn.execute("VACUUM INTO ?", (str(backup),))
            with sqlite3.connect(f"file:{backup}?mode=ro", uri=True) as snapshot:
                assert snapshot.execute("PRAGMA quick_check").fetchone()[0] == "ok"
                assert snapshot.execute("SELECT COUNT(*) FROM agent_runs").fetchone()[0] == 2
            print("PASS: agent journal constraints, CAS rollback and SQLite online snapshot")
        finally:
            conn.close()


if __name__ == "__main__":
    exercise(SOURCE.read_text(encoding="utf-8"))
