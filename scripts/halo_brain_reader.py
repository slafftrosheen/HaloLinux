#!/usr/bin/env python3
"""Read-only import-store API core. Imported text is data, never authorization.

This deliberately narrower policy is not Android's complete cognition policy.
See docs/BRAIN-API.md. No provider calls, model execution or database mutation.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
import hashlib
import re
import sqlite3
import time
from typing import Callable

import halo_brain_archive as archive
import halo_brain_import as store
from halo_brain_records import KINDS, PATH_BY_KIND, _record, _valid, canonical

API_VERSION = 1
POLICY = 'explicit-memory-v1'
SCOPES = frozenset(('GLOBAL', 'PROJECT', 'DEVICE', 'CONVERSATION', 'WORKSPACE_OBJECT'))
MEMORY_KINDS = frozenset(('PREFERENCE', 'FACT', 'PERSON', 'PLACE', 'DEVICE', 'PROJECT', 'RELATIONSHIP'))
ORIGINS = frozenset(('USER_EXPLICIT', 'USER_CORRECTION', 'MODEL_INFERRED', 'EPISODE_DERIVED', 'WORKSPACE_DERIVED', 'IMPORT'))
ROUTES = frozenset(('/v1/status', '/v1/records/list', '/v1/records/read', '/v1/memory/search'))
MAX_RESPONSE = 8 * 1024**2


class ApiError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code, self.status = code, status


def require(condition: bool, code: str = 'invalid_request', status: int = 400) -> None:
    if not condition:
        raise ApiError(code, status)


def failure(code: str, status: int) -> tuple[int, dict]:
    return status, {'api_version': API_VERSION, 'ok': False, 'error': {'code': code}}


@dataclass(frozen=True)
class QueryLimits:
    scan_rows: int = 200
    history_rows: int = 64
    payload_bytes: int = 16 * 1024**2
    sql_steps: int = 2_000_000
    seconds: float = 2.0


class Budget:
    def __init__(self, limits: QueryLimits):
        self.limits = limits
        self.deadline = time.monotonic() + limits.seconds
        self.steps = self.bytes = 0
        self.exceeded = False

    def progress(self) -> int:
        self.steps += 1000
        self.exceeded = self.steps > self.limits.sql_steps or time.monotonic() > self.deadline
        return int(self.exceeded)

    def charge(self, size: int = 0) -> None:
        self.bytes += size
        if self.bytes > self.limits.payload_bytes or time.monotonic() > self.deadline:
            self.exceeded = True
            raise ApiError('query_budget_exceeded', 422)


@dataclass
class Record:
    fingerprint: str
    value: dict
    citation: dict


# CASE avoids fetching an unbounded corrupt payload. All user selectors use binds.
_ROW = '''v.kind,v.key,v.fingerprint,
    CASE WHEN length(v.payload)<=1048576 THEN v.payload ELSE NULL END,
    a.source,a.archive_id,a.content_hash,v.id'''


class BrainReader:
    def __init__(self, path, *, sources: list[str] | tuple[str, ...],
                 allow_inspection: bool = False, clock: Callable[[], int] | None = None,
                 limits: QueryLimits = QueryLimits()):
        require(isinstance(sources, (tuple, list)) and 1 <= len(sources) <= 16)
        for source in sources:
            try: store._source(source)
            except archive.ArchiveError: raise ApiError('invalid_source') from None
        require(type(allow_inspection) is bool)
        require(all(type(getattr(limits, k)) is int and getattr(limits, k) > 0
                    for k in ('scan_rows', 'history_rows', 'payload_bytes', 'sql_steps'))
                and type(limits.seconds) in (int, float) and 0 < limits.seconds <= 30)
        self.path, self.sources = path, frozenset(sources)
        self.allow_inspection, self.limits = allow_inspection, limits
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)
        # Pin this process to a store identity; replacement is not silently accepted.
        with store._store(path) as db:
            self.identity = db.execute('SELECT store_id FROM meta WHERE singleton=1').fetchone()[0]

    def _request(self, path: str, data: dict) -> dict:
        require(isinstance(data, dict))
        if path == '/v1/status':
            require(not data)
            return {}
        allowed = {'source', 'kind', 'intent', 'key', 'snapshot'} if path.endswith('/read') else (
            {'source', 'kind', 'intent', 'limit', 'cursor'} if path.endswith('/list') else
            {'source', 'scope', 'scope_id', 'query', 'limit', 'cursor'})
        require(set(data) <= allowed)
        source = data.get('source')
        try: store._source(source)
        except archive.ArchiveError: raise ApiError('invalid_source') from None
        require(source in self.sources, 'source_not_allowed', 403)
        out = dict(data)
        if path.startswith('/v1/records/'):
            require(self.allow_inspection and data.get('intent') == 'inspect', 'inspection_disabled', 403)
            kind = data.get('kind')
            require(isinstance(kind, str) and kind in KINDS)
            if path.endswith('/read'):
                require(_valid(data.get('key'), 'id'))
                if 'snapshot' in data:
                    require(isinstance(data['snapshot'], str) and re.fullmatch('[0-9a-f]{64}', data['snapshot']) is not None)
                return out
        else:
            scope, scope_id, query = data.get('scope'), data.get('scope_id'), data.get('query')
            require(isinstance(scope, str) and scope in SCOPES)
            require(scope_id is None if scope == 'GLOBAL' else _valid(scope_id, 'id') and len(scope_id) <= 200)
            require(isinstance(query, str) and 0 < len(query) <= 256 and bool(query.strip())
                    and all(ord(c) >= 32 and ord(c) != 127 and not 0xD800 <= ord(c) <= 0xDFFF for c in query))
            out['scope_id'] = scope_id
        limit = data.get('limit', 20)
        require(type(limit) is int and 1 <= limit <= (100 if path.endswith('/list') else 50))
        out['limit'] = limit
        if 'cursor' in data:
            require(isinstance(data['cursor'], str) and 1 <= len(data['cursor']) <= 9000)
        return out

    def dispatch(self, path: str, data: dict) -> tuple[int, dict]:
        budget = Budget(self.limits)
        try:
            require(isinstance(path, str) and path in ROUTES, 'not_found', 404)
            request = self._request(path, data)
            with store._store(self.path) as db:
                db.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MAX_RESPONSE)
                db.execute('PRAGMA busy_timeout=0')
                db.set_progress_handler(budget.progress, 1000)
                db.execute('BEGIN')
                try:
                    identity, generation = db.execute('SELECT store_id,generation FROM meta WHERE singleton=1').fetchone()
                    require(identity == self.identity, 'store_replaced', 409)
                    snapshot = hashlib.sha256(canonical([identity, generation])).hexdigest()
                    if path == '/v1/status':
                        result = dict(sources=sorted(self.sources), inspection_enabled=self.allow_inspection,
                                      schema_version=store.VERSION, read_only=True, transport='unix-only',
                                      policy=POLICY, runtime_activation=False)
                    elif path.endswith('/read'):
                        require(request.get('snapshot', snapshot) == snapshot, 'snapshot_changed', 409)
                        record = self._selected(db, request['source'], request['kind'], request['key'], budget)
                        require(record is not None, 'record_not_found', 404)
                        result = dict(record=record.value, citation=record.citation, usage='inspection-only')
                    else:
                        result = self._page(db, path, request, snapshot, budget)
                    result.update(snapshot=snapshot, store_generation=generation)
                finally:
                    db.set_progress_handler(None, 0)
                    db.execute('ROLLBACK')
            envelope = {'api_version': API_VERSION, 'ok': True, 'result': result}
            require(len(canonical(envelope)) <= MAX_RESPONSE, 'response_too_large', 422)
            return 200, envelope
        except ApiError as error:
            return failure(error.code, error.status)
        except (archive.ArchiveError, sqlite3.Error, OSError, ValueError, TypeError, KeyError, OverflowError):
            return failure('query_budget_exceeded', 422) if budget.exceeded else failure('store_unavailable', 503)

    def _checked(self, row, source: str, kind: str, key: str, budget: Budget) -> Record:
        require(row is not None, 'store_invalid', 503)
        row_kind, row_key, fingerprint, raw, row_source, archive_id, content_hash, version = row
        require((row_kind, row_key, row_source) == (kind, key, source)
                and isinstance(raw, bytes) and len(raw) <= 1024**2, 'store_invalid', 503)
        budget.charge(len(raw))
        try: parsed_kind, parsed_key, value = _record(PATH_BY_KIND[kind], raw, archive.Limits())
        except (archive.ArchiveError, ValueError, KeyError): raise ApiError('store_invalid', 503) from None
        require((parsed_kind, parsed_key) == (kind, key)
                and hashlib.sha256(canonical(value)).hexdigest() == fingerprint, 'store_invalid', 503)
        require(isinstance(archive_id, str) and 0 < len(archive_id) <= 128
                and isinstance(content_hash, str) and re.fullmatch('[0-9a-f]{64}', content_hash) is not None
                and type(version) is int and version > 0, 'store_invalid', 503)
        return Record(fingerprint, value, dict(source=source, kind=kind, key=key, version=version,
            archive_id=archive_id, archive_content_sha256=content_hash, record_sha256=fingerprint))

    def _selected(self, db, source: str, kind: str, key: str, budget: Budget) -> Record | None:
        row = db.execute(f'''SELECT {_ROW} FROM current_records c
            LEFT JOIN versions v ON v.id=c.version_id LEFT JOIN archives a ON a.id=v.archive
            WHERE c.source=? AND c.kind=? AND c.key=?''', (source, kind, key)).fetchone()
        return None if row is None else self._checked(row, source, kind, key, budget)

    def _stable(self, db, source: str, kind: str, key: str, record: Record,
                budget: Budget, predicate: Callable[[dict], bool] = lambda value: True) -> bool:
        rows = db.execute(f'''SELECT {_ROW} FROM archives a JOIN versions v ON v.archive=a.id
            WHERE a.source=? AND v.kind=? AND v.key=? ORDER BY a.id DESC LIMIT ?''',
            (source, kind, key, self.limits.history_rows + 1))
        # Depth overflow is a policy exclusion, not permission to ignore old history.
        count = 0
        for index, row in enumerate(rows):
            if index >= self.limits.history_rows: return False
            count += 1
            prior = self._checked(row, source, kind, key, budget)
            if index == 0 and prior.fingerprint != record.fingerprint: return False
            if not predicate(prior.value): return False
        return count > 0

    def _workspace(self, db, source, key, revision, budget) -> bool:
        if not _valid(key, 'id'): return False
        record = self._selected(db, source, 'workspace', key, budget)
        if record is None or not self._stable(db, source, 'workspace', key, record, budget,
                                              lambda v: v['lifecycle'] == 'ACTIVE'): return False
        if revision is not None:
            if isinstance(revision, str):
                if re.fullmatch('[0-9]{1,10}', revision) is None: return False
                revision = int(revision)
            if type(revision) is not int or revision < 0: return False
            note = self._selected(db, source, 'note', key, budget)
            if note is None or note.value['revision'] != revision: return False
            if not self._stable(db, source, 'note', key, note, budget): return False
        return True

    def _conversation(self, db, source, key, budget) -> bool:
        record = self._selected(db, source, 'conversation', key, budget)
        return record is not None and self._stable(db, source, 'conversation', key, record, budget,
                                                   lambda v: v['active'] is True and v['archived'] is False)

    def _evidence(self, db, source, value, budget):
        raw = value.get('evidenceJson', '[]')
        if len(raw) > 16_384: return None
        try:
            wrapper = archive._json_object(b'{"refs":' + raw.encode('utf-8') + b'}', archive.Limits())
            if set(wrapper) != {'refs'}: return None
            refs = wrapper['refs']
        except (ValueError, archive.ArchiveError, UnicodeError): return None
        if not isinstance(refs, list) or len(refs) > 12: return None
        for ref in refs:
            if not isinstance(ref, dict) or not set(ref) <= {'kind', 'id', 'revision'}: return None
            kind, key, revision = ref.get('kind'), ref.get('id'), ref.get('revision')
            if not _valid(key, 'id') or len(key) > 200: return None
            if revision is not None and (not isinstance(revision, str) or len(revision) > 80): return None
            if kind in ('USER_ACTION', 'IMPORTED'):
                pass  # Retain references, never treat them as independent proof of trust.
            elif kind == 'WORKSPACE_OBJECT':
                if not self._workspace(db, source, key, revision, budget): return None
            elif kind == 'CONVERSATION_TURN':
                turn = self._selected(db, source, 'turn', key, budget)
                if revision is not None or turn is None: return None
                if not self._stable(db, source, 'turn', key, turn, budget): return None
                if not self._conversation(db, source, turn.value['conversationId'], budget): return None
            else:
                return None  # Agent/routine/episode semantics need the future native adapter.
        return refs

    def _candidate(self, db, source: str, record: Record, request: dict, budget: Budget):
        v = record.value
        if (v['status'] != 'ACTIVE' or v.get('userConfirmed') is not True
                or v['scope'] != request['scope'] or v.get('scopeId') != request['scope_id']
                or v['kind'] not in MEMORY_KINDS or v.get('origin', 'IMPORT') not in ORIGINS): return None
        content = v['content']
        if not content.strip() or any(0xD800 <= ord(c) <= 0xDFFF for c in content): return None
        if len(content.encode('utf-16-le')) // 2 > 1000: return None
        if v['createdAt'] < 0 or v['updatedAt'] < v['createdAt']: return None
        confidence = v.get('confidence')
        if confidence is not None and not 0 <= confidence <= 1: return None
        if v.get('validUntil') is not None and v['validUntil'] <= self.clock(): return None
        if v.get('evidenceCount', 0) < 0 or v.get('sourceEpisodeId') is not None: return None
        if v.get('sourceObjectId') is not None:
            if not self._workspace(db, source, v['sourceObjectId'], v.get('sourceRevision'), budget): return None
        elif v.get('sourceRevision') is not None:
            return None
        if v['scope'] == 'WORKSPACE_OBJECT' and not self._workspace(db, source, v['scopeId'], None, budget): return None
        if v['scope'] == 'CONVERSATION' and not self._conversation(db, source, v['scopeId'], budget): return None
        if not self._stable(db, source, 'memory', v['id'], record, budget, lambda prior: prior['status'] == 'ACTIVE'): return None
        evidence = self._evidence(db, source, v, budget)
        if evidence is None or request['query'].casefold() not in content.casefold(): return None
        return dict(content=content, kind=v['kind'], scope=v['scope'], scope_id=v.get('scopeId'),
                    confidence=confidence, evidence=evidence, evidence_count=v.get('evidenceCount', 0),
                    source_object_id=v.get('sourceObjectId'), source_revision=v.get('sourceRevision'),
                    updated_at_ms=v['updatedAt'], valid_until_ms=v.get('validUntil'),
                    citation=record.citation, trust='imported-unverified', usage='local-context-candidate')

    def _page(self, db, path, request, snapshot, budget) -> dict:
        filters = {k: v for k, v in request.items() if k != 'cursor'} | {'endpoint': path}
        fingerprint = hashlib.sha256(canonical(filters)).hexdigest()
        after = ''
        if 'cursor' in request:
            try:
                token = request['cursor']
                require(re.fullmatch('[A-Za-z0-9_-]+', token) is not None, 'invalid_cursor')
                raw = base64.b64decode(token + '=' * (-len(token) % 4), altchars=b'-_', validate=True)
                cursor = archive._json_object(raw, archive.Limits(max_json_depth=8))
                require(set(cursor) == {'snapshot', 'filter', 'after'} and _valid(cursor['after'], 'id'), 'invalid_cursor')
            except (ValueError, archive.ArchiveError): raise ApiError('invalid_cursor') from None
            require(cursor['snapshot'] == snapshot and cursor['filter'] == fingerprint, 'cursor_mismatch', 409)
            after = cursor['after']
        source = request['source']
        kind = request['kind'] if path.endswith('/list') else 'memory'
        limit = request['limit']
        scan = limit if path.endswith('/list') else self.limits.scan_rows
        keys = db.execute('''SELECT key FROM current_records WHERE source=? AND kind=? AND key>?
            ORDER BY key LIMIT ?''', (source, kind, after, scan)).fetchall()
        results, scanned, last = [], 0, after
        for (key,) in keys:
            require(_valid(key, 'id'), 'store_invalid', 503)
            record = self._selected(db, source, kind, key, budget)
            require(record is not None, 'store_invalid', 503)
            scanned += 1; last = key
            value = key if path.endswith('/list') else self._candidate(db, source, record, request, budget)
            if value is not None: results.append(value)
            if len(results) == limit: break
        more = db.execute('SELECT 1 FROM current_records WHERE source=? AND kind=? AND key>? LIMIT 1',
                          (source, kind, last)).fetchone() is not None
        cursor = base64.urlsafe_b64encode(canonical(dict(snapshot=snapshot, filter=fingerprint, after=last))).decode().rstrip('=') if more else None
        budget.charge()
        if path.endswith('/list'):
            return dict(keys=results, next_cursor=cursor, usage='inspection-only')
        return dict(items=results, next_cursor=cursor, scanned_records=scanned,
                    policy=POLICY, cloud_authorization=False, evaluated_at_ms=self.clock())
