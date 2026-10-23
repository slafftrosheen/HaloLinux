"""Synthetic archives only: real 051 verification + 052 staging/import for API tests."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

import halo_brain_archive as archive
import halo_brain_import as importer
from halo_brain_records import PATH_BY_KIND, canonical, stage_archive


def memory(key='m1', **changes):
    return dict(id=key, content='Use Celsius for temperature. Sveiki Привет.',
                kind='PREFERENCE', scope='GLOBAL', createdAt=1000, updatedAt=2000,
                confidence=None, status='ACTIVE', origin='USER_EXPLICIT',
                evidenceJson='[]', evidenceCount=0, userConfirmed=True) | changes


def workspace(key='n1', **changes):
    return dict(id=key, type='NOTE', title='Synthetic private title', createdAt=1000,
                updatedAt=2000, source='USER_CREATED', lifecycle='ACTIVE',
                inInbox=False, indexingState='INDEXED', contentRef=key) | changes


def conversation(key='c1', **changes):
    return dict(id=key, title='Synthetic conversation', createdAt=1000,
                updatedAt=2000, active=True, archived=False) | changes


def make_archive(path: Path, records: dict[str, list[dict]], archive_id: str):
    entries = {p: b'' for paths in archive.SECTION_PATHS.values() for p in paths}
    for p in archive.SUPPLEMENTAL: entries[p] = b''
    entries['settings/user-settings.json'] = canonical({'values': {}, 'providerConfiguration': {}})
    for kind, values in records.items():
        p = PATH_BY_KIND[kind]
        entries[p] = canonical(values[0]) if kind == 'settings' else b''.join(canonical(v)+b'\n' for v in values)
    entries['README.md'] = b'Synthetic test data only.'
    entries['manifest.json'] = canonical(dict(format='halo-brain', schemaVersion=2,
        archiveId=archive_id, createdAtEpochMs=3000, appVersionName='test', appVersionCode=1,
        databaseSchemaVersion=1, profile='FULL_USER_BACKUP', encrypted=False, state='COMPLETE',
        sections=[dict(name=s, version=1, itemCount=sum(1 if p.endswith('.json') else
            len(entries[p].splitlines()) for p in paths)) for s, paths in archive.SECTION_PATHS.items()]))
    entries['checksums.sha256'] = ''.join(hashlib.sha256(data).hexdigest()+'  '+p+'\n' for p,data in entries.items()).encode()
    entries['archive.complete'] = canonical(dict(archiveId=archive_id,
        manifestSha256=hashlib.sha256(entries['manifest.json']).hexdigest(),
        checksumListSha256=hashlib.sha256(entries['checksums.sha256']).hexdigest()))
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as z:
        for p,data in entries.items(): z.writestr(p,data)


def import_records(root: Path, store: Path, records: dict, *, source='phone', policy='fail'):
    counter = len(list(root.glob('fixture-*.halobrain')))
    path = root / f'fixture-{counter}.halobrain'
    make_archive(path, records, f'fixture-{counter}')
    with stage_archive(path, temp_dir=root) as staged:
        plan = importer.plan_import(store, source, staged, policy=policy)
        return importer.apply_import(store, source, staged, expected_token=plan['plan_token'],
                                     policy=policy, allow_plaintext=True)
