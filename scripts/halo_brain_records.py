#!/usr/bin/env python3
"""Verified, bounded staging for Android schema-2 records; no runtime activation.

DTO reference: config/halobrain-contract.json. Raw record and entry bytes are kept
intact. Field validation does not interpret embedded evidence, tool payloads or URIs.
"""
from __future__ import annotations

from contextlib import ExitStack, closing, contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import zipfile

import halo_brain_archive as archive

MAX_RECORDS = 100_000


class RecordError(archive.ArchiveError):
    pass


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RecordError(message)


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode('ascii')


# Required fields use a plain type; '?' means nullable/omittable (Moshi null fields
# are often absent), '*' means an Android default exists. Never materialize defaults.
# i/l retain signed JVM Int/Long bounds rather than coercing bools or floats.
SPECS = {
    'workspace/objects.jsonl': ('workspace', 'id', {
        'id':'id', 'type':'s', 'title':'s', 'createdAt':'l', 'updatedAt':'l',
        'source':'s', 'lifecycle':'s', 'inInbox':'b', 'indexingState':'s', 'contentRef':'s'}),
    'notes/metadata.jsonl': ('note', 'objectId', {'objectId':'id', 'body':'s', 'revision':'i'}),
    'conversations/conversations.jsonl': ('conversation', 'id', {
        'id':'id', 'createdAt':'l', 'active':'b', 'title':'s', 'updatedAt':'l', 'archived':'b'}),
    'conversations/turns.jsonl': ('turn', 'id', {'id':'id', 'conversationId':'id',
        'userText':'s', 'assistantText':'s', 'timestamp':'l', 'metadata':'s'}),
    'memory/memories.jsonl': ('memory', 'id', {'id':'id', 'content':'s', 'kind':'s',
        'scope':'s', 'scopeId':'?s', 'createdAt':'l', 'updatedAt':'l', 'sourceObjectId':'?s',
        'sourceRevision':'?i', 'confidence':'?f', 'status':'s', 'origin':'*s',
        'evidenceJson':'*s', 'sourceEpisodeId':'?s', 'evidenceCount':'*i',
        'lastConfirmedAt':'?l', 'usageCount':'*l', 'lastUsedAt':'?l', 'validUntil':'?l',
        'fingerprint':'*s', 'userConfirmed':'*b'}),
    'memory/legacy.jsonl': ('legacy-memory', 'legacyId', {'legacyId':'i', 'content':'s',
        'timestamp':'l', 'sourceObjectId':'?s', 'sourceRevision':'?i'}),
    'memory/revisions.jsonl': ('memory-revision', 'id', {'id':'id', 'memoryId':'id',
        'previousContent':'s', 'newContent':'s', 'changedAt':'l', 'reason':'s'}),
    'memory/episodes.jsonl': ('episode', 'id', {'id':'id', 'runId':'id', 'json':'s',
        'endedAt':'l', 'importance':'i', 'usageCount':'l', 'lastReferencedAt':'?l'}),
    'memory/suggestion-decisions.jsonl': ('suggestion-decision', 'id', {'id':'id',
        'fingerprint':'s', 'kind':'s', 'state':'s', 'createdAt':'l', 'updatedAt':'l',
        'evidenceCount':'i', 'dismissedAt':'?l', 'neverAgain':'b'}),
    'memory/reflection-statuses.jsonl': ('reflection-status', 'id', {'id':'id',
        'policyVersion':'i', 'status':'s', 'mode':'s', 'updatedAt':'l'}),
    'working-set/entries.jsonl': ('working-set', 'objectId', {'objectId':'id', 'addedAt':'l', 'source':'s'}),
    'workspace/provenance.jsonl': ('provenance', 'objectId', {'objectId':'id', 'sourceId':'s',
        'revision':'s', 'metadata':'s'}),
    'relations/relations.jsonl': ('relation', 'id', {'id':'id', 'fromId':'id', 'type':'s',
        'toId':'id', 'createdAt':'l'}),
    'files/index.jsonl': ('file', 'objectId', {'objectId':'id', 'uri':'s', 'displayName':'s',
        'mimeType':'s', 'sizeBytes':'?l', 'modifiedAt':'?l', 'ownedCopyPath':'?s',
        'permissionPersisted':'b', 'ownership':'s', 'availability':'s', 'sha256':'?s'}),
    'agent/runs.jsonl': ('agent-run', 'id', {'id':'id', 'interactionId':'*s', 'request':'s',
        'startedAt':'l', 'updatedAt':'*l', 'completedAt':'?l', 'state':'s',
        'parentRunId':'?s', 'rootRunId':'?s', 'delegatedRole':'?s', 'delegationDepth':'*i',
        'pinnedProviderId':'?s', 'pinnedModelId':'?s', 'pinnedTargetLocal':'?b', 'delegatedInputRefs':'?s'}),
    'agent/steps.jsonl': ('agent-step', 'id', {'id':'id', 'runId':'id', 'interactionId':'*s',
        'ordinal':'*i', 'stepType':'s', 'schemaVersion':'*i', 'payload':'s',
        'idempotencyKey':'?s', 'state':'*s', 'timestamp':'l'}),
    'reminders/reminders.jsonl': ('reminder', 'id', {'id':'id', 'message':'s',
        'triggerAtEpochMs':'l', 'precision':'s'}),
    'devices/configuration.jsonl': ('device', 'id', {'id':'id', 'displayName':'s',
        'deviceType':'s', 'firmwareVersion':'?s', 'preferredAutoConnect':'b'}),
    'settings/user-settings.json': ('settings', None, {'values':'ms',
        'providerConfiguration':'mb', 'skillEnabled':'*mb', 'booleanValues':'*mb',
        'integerValues':'*mi', 'appearanceMode':'?s'}),
}
PATH_BY_KIND = {spec[0]: path for path, spec in SPECS.items()}
KINDS = frozenset(PATH_BY_KIND)
REFERENCES = {
    'note': (('objectId','workspace'),),
    'turn': (('conversationId','conversation'),),
    'memory': (('sourceObjectId','workspace'), ('sourceEpisodeId','episode')),
    'legacy-memory': (('sourceObjectId','workspace'),),
    'memory-revision': (('memoryId','memory'),),
    'episode': (('runId','agent-run'),),
    'working-set': (('objectId','workspace'),),
    'provenance': (('objectId','workspace'),),
    'relation': (('fromId','workspace'), ('toId','workspace')),
    'file': (('objectId','workspace'),),
    'agent-run': (('parentRunId','agent-run'), ('rootRunId','agent-run')),
    'agent-step': (('runId','agent-run'),),
}


def _valid(value: object, kind: str) -> bool:
    if kind == 's': return isinstance(value, str)
    if kind == 'id':
        return isinstance(value, str) and 0 < len(value) <= 512 and bool(value.strip()) and all(32 <= ord(c) and ord(c) != 127 and not 0xD800 <= ord(c) <= 0xDFFF for c in value)
    if kind == 'b': return type(value) is bool
    if kind in ('i','l'):
        bits = 32 if kind == 'i' else 64
        return type(value) is int and -(2**(bits-1)) <= value < 2**(bits-1)
    if kind == 'f': return type(value) in (int,float) and -3.4028235e38 <= value <= 3.4028235e38
    if kind.startswith('m'):
        return isinstance(value, dict) and all(isinstance(k,str) and _valid(v,kind[1:]) for k,v in value.items())
    raise AssertionError('Unknown fixed DTO field type')


def _record(path: str, raw: bytes, limits: archive.Limits) -> tuple[str,str,dict]:
    value = archive._json_object(raw, limits)
    kind, key_name, fields = SPECS[path]
    for name, description in fields.items():
        optional = description[0] in ('?','*')
        if name not in value:
            require(optional, 'Required Android record field is missing.')
            continue
        if description.startswith('?') and value[name] is None: continue
        field_type = description[1:] if optional else description
        require(_valid(value[name], field_type), 'Android record field has an invalid type or bound.')
    key = str(value[key_name]) if key_name else 'user-settings'
    return kind, key, value


@dataclass
class StagedArchive:
    path: Path
    connection: sqlite3.Connection
    content_hash: str
    source_hash: str
    archive_id: str
    profile: str
    record_count: int
    entry_count: int
    expanded_bytes: int
    missing_file_payloads: int


def _create_stage(db: sqlite3.Connection) -> None:
    # Disposable private staging only. The durable target uses FULL + DELETE.
    db.execute('PRAGMA journal_mode=OFF')
    db.execute('PRAGMA synchronous=OFF')
    db.execute('PRAGMA cache_size=-4096')
    db.execute('PRAGMA temp_store=FILE')
    db.execute('CREATE TABLE entries(id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, data BLOB NOT NULL)')
    db.execute('CREATE TABLE records(kind TEXT NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(kind,key))')
    db.execute('CREATE TABLE refs(kind TEXT NOT NULL, key TEXT NOT NULL, target_kind TEXT NOT NULL, target_key TEXT NOT NULL)')


def _write_entry(db: sqlite3.Connection, z: zipfile.ZipFile, info: zipfile.ZipInfo) -> None:
    row = db.execute('INSERT INTO entries(path,sha256,size,data) VALUES(?,?,?,zeroblob(?))',
        (info.filename, '', info.file_size, info.file_size)).lastrowid
    digest = hashlib.sha256()
    with db.blobopen('entries','data',row) as output, z.open(info) as source:
        while block := source.read(archive.CHUNK):
            digest.update(block); output.write(block)
    db.execute('UPDATE entries SET sha256=? WHERE id=?', (digest.hexdigest(),row))


def _file_payload(db: sqlite3.Connection, value: dict) -> int:
    # Other owners are Android references, not a permission to access local files.
    if value['ownership'] != 'HALO_OWNED': return 0
    pointer = value.get('ownedCopyPath')
    if not pointer or not pointer.startswith('archive:'): return 1
    path = pointer[len('archive:'):]
    require(path.startswith('files/content/'), 'File record points outside archive content.')
    actual = db.execute('SELECT size,sha256 FROM entries WHERE path=?',(path,)).fetchone()
    require(actual is not None, 'File record references a missing archive payload.')
    if value.get('sizeBytes') is not None:
        require(value['sizeBytes'] == actual[0], 'File metadata size does not match its payload.')
    if value.get('sha256') is not None:
        require(value['sha256'].lower() == actual[1], 'File metadata hash does not match its payload.')
    return 0


@contextmanager
def stage_archive(path: Path | str, password: bytes | bytearray | None = None, *,
                  temp_dir: Path | str | None = None, limits: archive.Limits = archive.Limits(),
                  max_records: int = MAX_RECORDS):
    """Yield verified private staging; cleanup on success, cancellation or exception.

    The target store is not opened here. One immutable private ZIP snapshot feeds
    both validation and staging, so replacing the source later cannot change data.
    """
    require(type(max_records) is int and 0 < max_records <= MAX_RECORDS, 'Invalid import record limit.')
    require(all(type(v) is int and v > 0 for v in vars(limits).values()), 'Invalid archive limits.')
    with ExitStack() as stack:
        directory = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix='halo-import-',dir=temp_dir)))
        snapshot = stack.enter_context(tempfile.TemporaryFile(mode='w+b',dir=directory))
        size, source_hash = archive._snapshot(Path(path),snapshot,limits)
        encrypted = snapshot.read(len(archive.MAGIC)) == archive.MAGIC
        snapshot.seek(0)
        stream = snapshot
        if encrypted:
            stream = stack.enter_context(tempfile.TemporaryFile(mode='w+b',dir=directory))
            archive._decrypt(snapshot,stream,size,password)
        inventory = archive._inspect_zip(stream,encrypted,limits)
        require(sum(inventory['record_counts'].values()) <= max_records, 'Import record count limit exceeded.')
        stage_path = directory / 'staging.sqlite3'
        descriptor = os.open(stage_path,os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_CLOEXEC,0o600)
        os.fchmod(descriptor,0o600); os.close(descriptor)
        db = stack.enter_context(closing(sqlite3.connect(stage_path,isolation_level=None)))
        _create_stage(db)
        db.execute('BEGIN')
        count = missing = 0
        with zipfile.ZipFile(stream) as z:
            manifest = archive._json_object(z.read('manifest.json'),limits)
            content_hash = hashlib.sha256(z.read('manifest.json') + b'\0' + z.read('checksums.sha256')).hexdigest()
            for info in z.infolist(): _write_entry(db,z,info)
            for path, _spec in SPECS.items():
                if path not in z.namelist(): continue
                with z.open(path) as source:
                    if path.endswith('.json'):
                        raws = (source.read(archive.METADATA[path]+1),)
                    else:
                        # readline has a bound even though these same bytes were verified.
                        raws = iter(lambda: source.readline(limits.max_record_bytes+1), b'')
                    for raw in raws:
                        count += 1
                        require(count <= max_records, 'Import record count limit exceeded.')
                        require(len(raw) <= limits.max_record_bytes, 'Import record size limit exceeded.')
                        kind,key,value = _record(path,raw,limits)
                        try:
                            db.execute('INSERT INTO records VALUES(?,?,?,?)',
                                (kind,key,hashlib.sha256(canonical(value)).hexdigest(),raw))
                        except sqlite3.IntegrityError:
                            raise RecordError('Duplicate Android record identity.') from None
                        for field,target_kind in REFERENCES.get(kind,()):
                            target = value.get(field)
                            if target is not None:
                                require(_valid(target,'id'), 'Invalid Android reference identity.')
                                db.execute('INSERT INTO refs VALUES(?,?,?,?)',(kind,key,target_kind,target))
                        if kind == 'file': missing += _file_payload(db,value)
        db.execute('COMMIT')
        db.execute('PRAGMA query_only=ON')
        yield StagedArchive(stage_path,db,content_hash,source_hash,manifest['archiveId'],
            manifest['profile'],count,inventory['entries'],inventory['expanded_bytes'],missing)
