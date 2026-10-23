#!/usr/bin/env python3
"""Offline Android archive import into a separate private, plaintext SQLite store.

init -> plan -> apply with the returned token and --accept-plaintext. No networking,
no halo.db writes, no native runtime retrieval or settings/reminder/tool activation.
"""
from __future__ import annotations

from contextlib import contextmanager
import fcntl
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time
import uuid
import warnings

import halo_brain_archive as archive
from halo_brain_records import KINDS, PATH_BY_KIND, StagedArchive, _record, canonical, stage_archive

DATABASE = 'brain.sqlite3'
APPLICATION_ID = 0x4842494D  # HBIM; not the Halo JVM database.
VERSION = 1
POLICIES = ('fail', 'keep-local', 'use-incoming')
SCHEMA = (
    'CREATE TABLE meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), store_id TEXT NOT NULL, generation INTEGER NOT NULL)',
    'CREATE TABLE archives(id INTEGER PRIMARY KEY, source TEXT NOT NULL, archive_id TEXT NOT NULL, content_hash TEXT NOT NULL, source_hash TEXT NOT NULL, profile TEXT NOT NULL, imported_at_ms INTEGER NOT NULL, policy TEXT NOT NULL, UNIQUE(source,archive_id), UNIQUE(source,content_hash))',
    'CREATE TABLE entries(id INTEGER PRIMARY KEY, archive INTEGER NOT NULL REFERENCES archives(id), path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, data BLOB NOT NULL, UNIQUE(archive,path))',
    'CREATE TABLE versions(id INTEGER PRIMARY KEY, archive INTEGER NOT NULL REFERENCES archives(id), kind TEXT NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL, payload BLOB NOT NULL, UNIQUE(archive,kind,key))',
    'CREATE TABLE current_records(source TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, version_id INTEGER NOT NULL REFERENCES versions(id), PRIMARY KEY(source,kind,key))',
)


class ImportError(archive.ArchiveError):
    """Fixed public diagnostics; never interpolate user content or paths."""


def require(ok: bool, message: str) -> None:
    if not ok: raise ImportError(message)


def _source(source: str) -> None:
    require(isinstance(source,str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}',source) is not None,
        'Source must be a short local label, not a path or peer credential.')


@contextmanager
def _directory(path: Path | str, create: bool = False):
    path = Path(path)
    if not path.is_absolute(): path = Path.cwd() / path
    require('..' not in path.parts and len(path.parts) > 1, 'Invalid private store directory.')
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    descriptor = os.open('/',flags)
    try:
        for index, part in enumerate(path.parts[1:]):
            last = index == len(path.parts)-2
            made = False
            if last and create:
                try: os.mkdir(part,0o700,dir_fd=descriptor); made = True
                except FileExistsError: pass
            try: child = os.open(part,flags,dir_fd=descriptor)
            except OSError:
                raise ImportError('Store directory is missing, inaccessible or contains a symlink.') from None
            os.close(descriptor); descriptor = child
            if made: os.fchmod(descriptor,0o700)
        info = os.fstat(descriptor)
        require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
            'Store directory must already be owned by this user with mode 0700.')
        try: fcntl.flock(descriptor,fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ImportError('Another import-store operation is running; retry when it finishes.') from None
        yield descriptor
    finally:
        os.close(descriptor)


def _check_files(directory: int, *, allow_empty: bool = False, recovery: bool = False) -> None:
    names = set(os.listdir(directory))
    require(names <= {DATABASE, DATABASE+'-journal'},
        'Store contains unexpected files or WAL sidecars; use a dedicated import directory.')
    require(allow_empty or DATABASE in names, 'Store is not initialized; run init explicitly.')
    require(DATABASE in names or not names, 'Orphan store journal found; no database was opened.')
    for name in names:
        info = os.stat(name,dir_fd=directory,follow_symlinks=False)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
            and stat.S_IMODE(info.st_mode) == 0o600,
            'Store files must be private regular files without symbolic or hard links.')
    # Check the fixed SQLite header before SQLite can perform even an explicitly
    # requested hot-journal recovery. Offsets 60/68 are user_version/application_id
    # in SQLite's documented big-endian database header.
    if DATABASE in names:
        descriptor = os.open(DATABASE,os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,dir_fd=directory)
        try:
            info = os.fstat(descriptor)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1
                and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o600,
                'Import database changed during inspection.')
            header = os.read(descriptor,100)
        finally: os.close(descriptor)
        require(len(header) == 100 and header[:16] == b'SQLite format 3\0'
            and int.from_bytes(header[60:64],'big') == VERSION
            and int.from_bytes(header[68:72],'big') == APPLICATION_ID,
            'Unrecognized import database or unsupported header; no SQLite connection was opened.')
    require(recovery or DATABASE+'-journal' not in names,
        'Interrupted store transaction detected; run recover explicitly before planning.')


def _uri(directory: int, mode: str) -> str:
    # A directory handle prevents replacing an ancestor path between checks and open.
    return f'file:/proc/self/fd/{directory}/{DATABASE}?mode={mode}'


def _connect(directory: int, writable: bool) -> sqlite3.Connection:
    db = sqlite3.connect(_uri(directory,'rw' if writable else 'ro'),uri=True,
        isolation_level=None,timeout=5.0)
    try:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA trusted_schema=OFF')
        db.execute('PRAGMA cache_size=-4096')
        db.execute('PRAGMA temp_store=FILE')
        if not writable: db.execute('PRAGMA query_only=ON')
        else: db.execute('PRAGMA synchronous=FULL')
        return db
    except BaseException:
        db.close(); raise


def _schema(db: sqlite3.Connection) -> None:
    require(db.execute('PRAGMA application_id').fetchone()[0] == APPLICATION_ID
        and db.execute('PRAGMA user_version').fetchone()[0] == VERSION,
        'Unrecognized import database or unsupported schema; refusing to modify it.')
    require(db.execute('PRAGMA journal_mode').fetchone()[0] == 'delete',
        'Import store requires its original DELETE journal mode.')
    actual = {row[0] for row in db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    require(actual == set(SCHEMA), 'Import database schema does not match the reviewed contract.')
    require(db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('trigger','view')").fetchone()[0] == 0,
        'Unexpected database triggers or views.')
    rows = db.execute('SELECT store_id,generation FROM meta WHERE singleton=1').fetchall()
    require(len(rows) == 1 and isinstance(rows[0][0],str) and len(rows[0][0]) == 36
        and type(rows[0][1]) is int and rows[0][1] >= 0, 'Invalid import store identity or generation.')


@contextmanager
def _store(path: Path | str, writable: bool = False):
    with _directory(path) as directory:
        _check_files(directory)
        db = _connect(directory,writable)
        try:
            _schema(db)
            yield db
        finally:
            db.close()  # Open transactions roll back, including BaseException paths.


def initialize_store(path: Path | str) -> dict:
    with _directory(path,create=True) as directory:
        _check_files(directory,allow_empty=True)
        if DATABASE in os.listdir(directory):
            db = _connect(directory,False)
            try: _schema(db)
            finally: db.close()
        else:
            descriptor = os.open(DATABASE,os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC,
                0o600,dir_fd=directory)
            os.fchmod(descriptor,0o600); os.close(descriptor)
            db = None
            try:
                db = _connect(directory,True)
                db.execute('BEGIN IMMEDIATE')
                for statement in SCHEMA: db.execute(statement)
                db.execute(f'PRAGMA application_id={APPLICATION_ID}')
                db.execute(f'PRAGMA user_version={VERSION}')
                db.execute('INSERT INTO meta VALUES(1,?,0)',(str(uuid.uuid4()),))
                db.execute('COMMIT')
                os.fsync(directory)
            except BaseException:
                if db is not None: db.close(); db = None
                for name in (DATABASE+'-journal',DATABASE):
                    try: os.unlink(name,dir_fd=directory)
                    except FileNotFoundError: pass
                raise
            finally:
                if db is not None: db.close()
    return {'initialized':True,'schema_version':VERSION,'storage':'private-plaintext','runtime_activation':False}


def _attach(db: sqlite3.Connection, staged: StagedArchive) -> None:
    db.execute('ATTACH DATABASE ? AS incoming',(staged.path.as_uri()+'?mode=ro',))


def _plan(db: sqlite3.Connection, source: str, staged: StagedArchive, policy: str) -> dict:
    _source(source)
    require(policy in POLICIES,'Unsupported conflict policy.')
    identity,generation = db.execute('SELECT store_id,generation FROM meta WHERE singleton=1').fetchone()
    known = db.execute('SELECT content_hash FROM archives WHERE source=? AND archive_id=?',
        (source,staged.archive_id)).fetchone()
    require(known is None or known[0] == staged.content_hash,
        'Archive identity was reused with different content; refusing an ambiguous import.')
    already = db.execute('SELECT 1 FROM archives WHERE source=? AND content_hash=?',
        (source,staged.content_hash)).fetchone() is not None
    created = same = conflicts = 0
    for fingerprint,current in db.execute('''SELECT r.fingerprint,v.fingerprint FROM incoming.records r
        LEFT JOIN current_records c ON c.source=? AND c.kind=r.kind AND c.key=r.key
        LEFT JOIN versions v ON v.id=c.version_id''',(source,)):
        if current is None: created += 1
        elif fingerprint == current: same += 1
        else: conflicts += 1
    unresolved = db.execute('''SELECT COUNT(*) FROM incoming.refs r
        WHERE NOT EXISTS(SELECT 1 FROM incoming.records n WHERE n.kind=r.target_kind AND n.key=r.target_key)
        AND NOT EXISTS(SELECT 1 FROM current_records c WHERE c.source=? AND c.kind=r.target_kind AND c.key=r.target_key)''',
        (source,)).fetchone()[0]
    # Content hash, not encryption nonce/compression, identifies the verified content.
    consent = {'format':'halo-import-plan-v1','store':identity,'generation':generation,
        'source':source,'policy':policy,'content_hash':staged.content_hash}
    return {'plan_token':hashlib.sha256(canonical(consent)).hexdigest(),
        'store_generation':generation,'content_sha256':staged.content_hash,'profile':staged.profile,
        'record_count':staged.record_count,'entry_count':staged.entry_count,'expanded_bytes':staged.expanded_bytes,
        'created_records':0 if already else created,'unchanged_records':staged.record_count if already else same,
        'conflicting_records':0 if already else conflicts,
        'updated_records':conflicts if not already and policy=='use-incoming' else 0,
        'retained_conflicts':conflicts if not already and policy=='keep-local' else 0,
        'unresolved_references':unresolved,'missing_file_payloads':staged.missing_file_payloads,
        'policy':policy,'already_imported':already,'can_apply':already or conflicts==0 or policy!='fail',
        'storage':'private-plaintext','runtime_activation':False,'deletions':0}


def plan_import(store: Path | str, source: str, staged: StagedArchive, *, policy: str = 'fail') -> dict:
    with _store(store) as db:
        _attach(db,staged)
        db.execute('BEGIN')
        try: return _plan(db,source,staged,policy)
        finally: db.execute('ROLLBACK')


def _copy_entries(db: sqlite3.Connection, staged: StagedArchive, archive_id: int) -> None:
    for row,path,expected,size in staged.connection.execute('SELECT id,path,sha256,size FROM entries ORDER BY id'):
        destination = db.execute('INSERT INTO entries(archive,path,sha256,size,data) VALUES(?,?,?,?,zeroblob(?))',
            (archive_id,path,expected,size,size)).lastrowid
        digest = hashlib.sha256()
        with staged.connection.blobopen('entries','data',row,readonly=True) as src, db.blobopen('entries','data',destination) as dst:
            while block := src.read(archive.CHUNK):
                digest.update(block); dst.write(block)
        require(digest.hexdigest() == expected, 'Staged entry changed; transaction cancelled.')


def _copy_records(db: sqlite3.Connection, staged: StagedArchive, archive_id: int, source: str, policy: str) -> None:
    for kind,key,fingerprint,payload in staged.connection.execute('SELECT kind,key,fingerprint,payload FROM records ORDER BY kind,key'):
        require(kind in PATH_BY_KIND, 'Invalid staged record kind; transaction cancelled.')
        parsed_kind,parsed_key,value = _record(PATH_BY_KIND[kind],payload,archive.Limits())
        require((parsed_kind,parsed_key) == (kind,key) and hashlib.sha256(canonical(value)).hexdigest() == fingerprint,
            'Staged record changed; transaction cancelled.')
        version = db.execute('INSERT INTO versions(archive,kind,key,fingerprint,payload) VALUES(?,?,?,?,?)',
            (archive_id,kind,key,fingerprint,payload)).lastrowid
        current = db.execute('''SELECT v.fingerprint FROM current_records c JOIN versions v ON v.id=c.version_id
            WHERE c.source=? AND c.kind=? AND c.key=?''',(source,kind,key)).fetchone()
        if current is None:
            db.execute('INSERT INTO current_records VALUES(?,?,?,?)',(source,kind,key,version))
        elif current[0] != fingerprint and policy == 'use-incoming':
            db.execute('UPDATE current_records SET version_id=? WHERE source=? AND kind=? AND key=?',
                (version,source,kind,key))


def apply_import(store: Path | str, source: str, staged: StagedArchive, *, expected_token: str,
                 policy: str = 'fail', allow_plaintext: bool = False) -> dict:
    require(allow_plaintext is True,'Import stores decrypted personal data; explicit plaintext-at-rest consent is required.')
    require(isinstance(expected_token,str) and re.fullmatch('[0-9a-f]{64}',expected_token) is not None,
        'A valid preview token is required before applying.')
    with _store(store,True) as db:
        _attach(db,staged)
        db.execute('BEGIN IMMEDIATE')
        try:
            plan = _plan(db,source,staged,policy)
            require(expected_token == plan['plan_token'],'Preview is stale or belongs to different content, store, source or policy; plan again.')
            require(plan['can_apply'],'Conflicting records require an explicit conflict policy and a new preview.')
            if plan['already_imported']:
                db.execute('ROLLBACK')
                return {**plan,'applied':False}
            row = db.execute('INSERT INTO archives(source,archive_id,content_hash,source_hash,profile,imported_at_ms,policy) VALUES(?,?,?,?,?,?,?)',
                (source,staged.archive_id,staged.content_hash,staged.source_hash,staged.profile,time.time_ns()//1_000_000,policy)).lastrowid
            _copy_entries(db,staged,row)
            _copy_records(db,staged,row,source,policy)
            db.execute('UPDATE meta SET generation=generation+1 WHERE singleton=1')
            require(db.execute('PRAGMA foreign_key_check').fetchone() is None,'Import store relationship integrity failed.')
            db.execute('COMMIT')
            return {**plan,'store_generation':plan['store_generation']+1,'applied':True}
        except BaseException:
            if db.in_transaction: db.execute('ROLLBACK')
            raise


def store_status(store: Path | str) -> dict:
    with _store(store) as db:
        return {'schema_version':VERSION,'store_generation':db.execute('SELECT generation FROM meta').fetchone()[0],
            'archives':db.execute('SELECT COUNT(*) FROM archives').fetchone()[0],
            'record_versions':db.execute('SELECT COUNT(*) FROM versions').fetchone()[0],
            'current_records':db.execute('SELECT COUNT(*) FROM current_records').fetchone()[0],
            'records_by_kind':dict(db.execute('SELECT kind,COUNT(*) FROM current_records GROUP BY kind')),
            'storage':'private-plaintext','runtime_activation':False}


def get_record(store: Path | str, source: str, kind: str, key: str) -> dict:
    _source(source)
    require(kind in KINDS and isinstance(key,str) and 0 < len(key) <= 512,'Invalid record selector.')
    with _store(store) as db:
        row = db.execute('''SELECT v.payload FROM current_records c JOIN versions v ON v.id=c.version_id
            WHERE c.source=? AND c.kind=? AND c.key=?''',(source,kind,key)).fetchone()
        require(row is not None,'Record was not found in the selected local source.')
        return archive._json_object(row[0],archive.Limits())


def list_records(store: Path | str, source: str, kind: str, *, limit: int = 50, offset: int = 0) -> dict:
    """Explicit local inspection: return IDs only, never automatic content output."""
    _source(source)
    require(kind in KINDS and type(limit) is int and 1 <= limit <= 200
        and type(offset) is int and 0 <= offset <= 2**63-1, 'Invalid record-list bounds.')
    with _store(store) as db:
        keys = [r[0] for r in db.execute('SELECT key FROM current_records WHERE source=? AND kind=? ORDER BY key LIMIT ? OFFSET ?',
            (source,kind,limit+1,offset))]
        return {'keys':keys[:limit], 'next_offset':offset+limit if len(keys)>limit else None,
            'kind':kind, 'runtime_activation':False}


def recover_store(store: Path | str) -> dict:
    """Explicitly let SQLite undo an interrupted transaction, never resume an import."""
    with _directory(store) as directory:
        _check_files(directory,recovery=True)
        db = _connect(directory,True)
        try:
            _schema(db)  # SQLite performs hot-journal rollback while accessing the DB.
            require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)],'Store integrity check failed; preserve the store for recovery.')
            require(db.execute('PRAGMA foreign_key_check').fetchone() is None,'Store reference check failed; preserve the store for recovery.')
        finally: db.close()
        # SQLite may leave a cold journal with a zeroed header. Only remove that
        # recognizable case, never an unexplained nonzero recovery journal.
        if DATABASE+'-journal' in os.listdir(directory):
            fd = os.open(DATABASE+'-journal',os.O_RDONLY | os.O_NOFOLLOW,dir_fd=directory)
            try: header = os.read(fd,8)
            finally: os.close(fd)
            require(not header or header == b'\0'*8,'Recovery journal remains active; preserve the store.')
            os.unlink(DATABASE+'-journal',dir_fd=directory); os.fsync(directory)
    return {'recovered':True,'runtime_activation':False}


class SafeParser(archive._Parser):
    pass


def main(argv: list[str] | None = None) -> int:
    parser = SafeParser(description=__doc__,allow_abbrev=False)
    sub = parser.add_subparsers(dest='command',required=True,parser_class=SafeParser)
    for command in ('init','status','recover','read','list','plan','apply'):
        p = sub.add_parser(command,allow_abbrev=False)
        p.add_argument('--store',type=Path,required=True,help='Dedicated private import-store directory, not the Halo data directory')
        if command in ('read','list','plan','apply'): p.add_argument('--source',required=True)
        if command == 'read':
            p.add_argument('--kind',required=True,choices=sorted(KINDS)); p.add_argument('--key',required=True)
        if command == 'list':
            p.add_argument('--kind',required=True,choices=sorted(KINDS))
            p.add_argument('--limit',type=int,default=50); p.add_argument('--offset',type=int,default=0)
        if command in ('plan','apply'):
            p.add_argument('archive',type=Path); p.add_argument('--policy',choices=POLICIES,default='fail')
            p.add_argument('--temp-dir',type=Path); p.add_argument('--password-stdin',action='store_true')
        if command == 'apply':
            p.add_argument('--plan-token',required=True)
            p.add_argument('--accept-plaintext',action='store_true',help='Consent to private but unencrypted storage of imported data')
    password = None
    try:
        args = parser.parse_args(argv)
        if args.command == 'init': result = initialize_store(args.store)
        elif args.command == 'status': result = store_status(args.store)
        elif args.command == 'recover': result = recover_store(args.store)
        elif args.command == 'read': result = get_record(args.store,args.source,args.kind,args.key)
        elif args.command == 'list': result = list_records(args.store,args.source,args.kind,limit=args.limit,offset=args.offset)
        else:
            if args.password_stdin:
                password = bytearray(sys.stdin.buffer.readline(4099))
                if password.endswith(b'\n'): password.pop()
                if password.endswith(b'\r'): password.pop()
                require(0 < len(password) <= 4096,'Password must contain 1 to 4096 UTF-8 bytes.')
            def operation():
                with stage_archive(args.archive,password,temp_dir=args.temp_dir) as staged:
                    if args.command == 'plan': return plan_import(args.store,args.source,staged,policy=args.policy)
                    return apply_import(args.store,args.source,staged,policy=args.policy,
                        expected_token=args.plan_token,allow_plaintext=args.accept_plaintext)
            try: result = operation()
            except archive.ArchiveError as error:
                if password is not None or not sys.stdin.isatty() or str(error) != 'Encrypted archive requires a password (hidden prompt or --password-stdin).': raise
                with open('/dev/tty','r+') as tty, warnings.catch_warnings():
                    warnings.simplefilter('error',getpass.GetPassWarning)
                    password = bytearray(getpass.getpass('Archive password: ',stream=tty).encode('utf-8'))
                result = operation()
        print(json.dumps(result,sort_keys=True,ensure_ascii=True,allow_nan=False))
        return 0
    except archive.ArchiveError as error:
        print(f'halo-brain-import: {error}',file=sys.stderr); return 2
    except KeyboardInterrupt:
        print('halo-brain-import: Operation interrupted; inspect store status before retrying.',file=sys.stderr); return 130
    except Exception:
        # sqlite/JSON/I/O exception text can contain data or filenames; never echo it.
        print('halo-brain-import: Operation failed. Check the input, store permissions and free disk space.',file=sys.stderr); return 2
    finally:
        if password is not None: password[:] = b'\0'*len(password)


if __name__ == '__main__':
    raise SystemExit(main())
