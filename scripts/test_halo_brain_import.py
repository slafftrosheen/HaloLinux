#!/usr/bin/env python3
"""Synthetic DTO-complete archive tests; never user data or a real Android export."""
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import sqlite3
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'scripts/halo_brain_import.py'
SECRET = 'private-content-never-in-plan'


def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()


def records():
    return {
        'workspace/objects.jsonl': [{'id': 'n1', 'type': 'NOTE', 'title': SECRET,
            'createdAt': 1, 'updatedAt': 2, 'source': 'USER', 'lifecycle': 'ACTIVE',
            'inInbox': True, 'indexingState': 'READY', 'contentRef': 'n1'}],
        'notes/metadata.jsonl': [{'objectId': 'n1', 'body': SECRET, 'revision': 1}],
        'conversations/conversations.jsonl': [{'id': 'c1', 'createdAt': 1, 'active': False,
            'title': SECRET, 'updatedAt': 3, 'archived': True}],
        'conversations/turns.jsonl': [{'id': 't1', 'conversationId': 'c1',
            'userText': SECRET, 'assistantText': 'Sveiki 🌍', 'timestamp': 2, 'metadata': '{}'}],
        'memory/memories.jsonl': [{'id': 'm1', 'content': SECRET, 'kind': 'FACT',
            'scope': 'WORKSPACE', 'scopeId': 'n1', 'createdAt': 1, 'updatedAt': 3,
            'sourceObjectId': 'n1', 'sourceRevision': 1, 'confidence': None,
            'status': 'REVOKED', 'origin': 'USER', 'evidenceJson': '[{"id":"n1"}]',
            'sourceEpisodeId': 'ep1', 'evidenceCount': 1, 'lastConfirmedAt': None,
            'usageCount': 0, 'lastUsedAt': None, 'validUntil': None,
            'fingerprint': 'fp', 'userConfirmed': False, 'futureField': {'keep': [1, None]}}],
        'memory/legacy.jsonl': [{'legacyId': 1, 'content': 'legacy', 'timestamp': 1,
            'sourceObjectId': None, 'sourceRevision': None}],
        'memory/revisions.jsonl': [{'id': 'rev1', 'memoryId': 'm1', 'previousContent': 'before',
            'newContent': SECRET, 'changedAt': 3, 'reason': 'user'}],
        'memory/episodes.jsonl': [{'id': 'ep1', 'runId': 'r1', 'json': '{}',
            'endedAt': 4, 'importance': 2, 'usageCount': 0, 'lastReferencedAt': None}],
        'memory/suggestion-decisions.jsonl': [{'id': 'd1', 'fingerprint': 'fp', 'kind': 'FACT',
            'state': 'DISMISSED', 'createdAt': 1, 'updatedAt': 3, 'evidenceCount': 1,
            'dismissedAt': 3, 'neverAgain': True}],
        'memory/reflection-statuses.jsonl': [{'id': 'rf1', 'policyVersion': 1,
            'status': 'DONE', 'mode': 'LOCAL', 'updatedAt': 3}],
        'working-set/entries.jsonl': [{'objectId': 'n1', 'addedAt': 2, 'source': 'USER'}],
        'workspace/provenance.jsonl': [{'objectId': 'n1', 'sourceId': 'local',
            'revision': '1', 'metadata': '{}'}],
        'relations/relations.jsonl': [{'id': 'rel1', 'fromId': 'n1', 'type': 'SOURCE',
            'toId': 'n1', 'createdAt': 2}],
        'files/index.jsonl': [{'objectId': 'n1', 'uri': 'content://DO-NOT-OPEN',
            'displayName': 'test.bin', 'mimeType': 'application/octet-stream',
            'sizeBytes': 256, 'modifiedAt': 2, 'ownedCopyPath': 'archive:files/content/n1/test.bin',
            'permissionPersisted': False, 'ownership': 'HALO_OWNED', 'availability': 'OWNED_LOCAL',
            'sha256': hashlib.sha256(bytes(range(256))).hexdigest()}],
        'agent/runs.jsonl': [{'id': 'r1', 'request': 'must not execute',
            'startedAt': 1, 'completedAt': None, 'state': 'RUNNING'}],
        'agent/steps.jsonl': [{'id': 'st1', 'runId': 'r1', 'stepType': 'TOOL',
            'payload': '{"tool":"delete_all"}', 'timestamp': 1}],
        'reminders/reminders.jsonl': [{'id': 'rem1', 'message': SECRET,
            'triggerAtEpochMs': 9999999, 'precision': 'EXACT'}],
        'devices/configuration.jsonl': [{'id': 'dev1', 'displayName': SECRET,
            'deviceType': 'GLASSES', 'firmwareVersion': None, 'preferredAutoConnect': True}],
    }


def archive_data(rows=None, archive_id='a1', *, encrypted=False, profile='FULL_USER_BACKUP', extra=None):
    from halo_brain_archive import SECTION_PATHS
    rows = rows if rows is not None else records()
    rows = {path: list(items) for path, items in rows.items()}
    if profile == 'BRAIN_ONLY':
        for f in rows['files/index.jsonl']:
            f.update(ownedCopyPath=None, uri='', availability='MISSING')
    entries = {p: b''.join(js(r) + b'\n' for r in items) for p, items in rows.items()}
    entries['settings/user-settings.json'] = js({'values': {'speech_locale': 'lv'},
        'providerConfiguration': {'openrouter': True}, 'booleanValues': {'policy_cloud_memories': False}})
    entries['README.md'] = b'# Synthetic archive\n'
    entries['notes/test_n1.md'] = ('# Note\n' + SECRET).encode()
    entries['memory/memories.md'] = b'# Synthetic memory\n'
    if profile != 'BRAIN_ONLY':
        entries['files/content/n1/test.bin'] = bytes(range(256))
    if extra: entries.update(extra)
    sections = []
    for name, paths in SECTION_PATHS.items():
        if all(p in entries for p in paths):
            sections.append({'name': name, 'version': 1, 'itemCount': sum(
                1 if p.endswith('.json') else len(entries[p].splitlines()) for p in paths)})
    manifest = {'format': 'halo-brain', 'schemaVersion': 2, 'archiveId': archive_id,
        'createdAtEpochMs': 4, 'appVersionName': 'synthetic', 'appVersionCode': 1,
        'databaseSchemaVersion': 1, 'profile': profile, 'encrypted': encrypted,
        'state': 'COMPLETE', 'sections': sections}
    entries = {'manifest.json': js(manifest), **entries}
    checksums = ''.join(hashlib.sha256(v).hexdigest() + '  ' + p + '\n' for p, v in entries.items()).encode()
    entries['checksums.sha256'] = checksums
    entries['archive.complete'] = js({'archiveId': archive_id,
        'manifestSha256': hashlib.sha256(entries['manifest.json']).hexdigest(),
        'checksumListSha256': hashlib.sha256(checksums).hexdigest()})
    # Nonseekable matches Java ZipOutputStream's data-descriptor layout.
    class Sink(io.BytesIO):
        def seekable(self): return False
        def seek(self, *args): raise io.UnsupportedOperation()
    sink = Sink()
    with zipfile.ZipFile(sink, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for path, raw in entries.items(): z.writestr(path, raw)
    raw = sink.getvalue()
    if encrypted:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        salt, nonce = os.urandom(16), os.urandom(12)
        header = b'HALOBRAIN' + struct.pack('>iii', 1, 100000, 16) + salt + struct.pack('>i', 12) + nonce
        key = hashlib.pbkdf2_hmac('sha256', 'pass-✓'.encode(), salt, 100000, 32)
        raw = header + AESGCM(key).encrypt(nonce, raw, header)
    return raw, entries


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(CLI.exists(), 'Transactional import CLI has not been implemented')
        self.mod = importlib.import_module('halo_brain_import')
        self.stage = importlib.import_module('halo_brain_records')
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.store = self.root / 'store'; self.archive = self.root / 'phone.halobrain'
        self.mod.initialize_store(self.store)

    def make(self, **kwargs):
        raw, entries = archive_data(**kwargs); self.archive.write_bytes(raw); return entries

    def run_import(self, source='phone', policy='fail', **kwargs):
        self.make(**kwargs)
        password = 'pass-✓'.encode() if kwargs.get('encrypted') else None
        with self.stage.stage_archive(self.archive, password, temp_dir=self.root) as staged:
            plan = self.mod.plan_import(self.store, source, staged, policy=policy)
            result = self.mod.apply_import(self.store, source, staged, expected_token=plan['plan_token'],
                policy=policy, allow_plaintext=True)
        return result

    def snapshot(self): return (self.store / 'brain.sqlite3').read_bytes()

    def test_cli_and_modules_exist(self): self.assertTrue(CLI.exists())
    def test_preserves_all_record_fields_and_nullable_confidence(self):
        result = self.run_import()
        self.assertEqual(result['created_records'], 19)
        self.assertEqual(self.mod.get_record(self.store, 'phone', 'memory', 'm1'), records()['memory/memories.jsonl'][0])
        self.assertFalse(result['runtime_activation'])
    def test_preserves_entry_bytes_and_binary_attachment(self):
        entries = self.make()
        with self.stage.stage_archive(self.archive) as staged:
            p = self.mod.plan_import(self.store, 'phone', staged)
            self.mod.apply_import(self.store, 'phone', staged, expected_token=p['plan_token'], allow_plaintext=True)
        with sqlite3.connect(self.store / 'brain.sqlite3') as db:
            saved = dict(db.execute('SELECT path, data FROM entries'))
        self.assertEqual(saved, entries)
    def test_repeated_import_is_noop(self):
        self.run_import(); before = self.snapshot(); result = self.run_import()
        self.assertTrue(result['already_imported']); self.assertEqual(self.snapshot(), before)
    def test_same_content_reencrypted_is_noop(self):
        self.run_import(encrypted=True); before = self.snapshot(); self.run_import(encrypted=True)
        self.assertEqual(self.snapshot(), before)
    def test_plan_is_read_only_and_stable(self):
        self.make(); before = self.snapshot(); files = sorted(p.name for p in self.store.iterdir())
        with self.stage.stage_archive(self.archive) as s:
            a = self.mod.plan_import(self.store, 'phone', s); b = self.mod.plan_import(self.store, 'phone', s)
        self.assertEqual(a,b); self.assertEqual(self.snapshot(),before)
        self.assertEqual(files,sorted(p.name for p in self.store.iterdir()))
        self.assertNotIn(SECRET,json.dumps(a)); self.assertNotIn('n1',json.dumps(a))
    def test_requires_plaintext_consent(self):
        self.make(); before = self.snapshot()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            with self.assertRaises(self.mod.ImportError):
                self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'])
        self.assertEqual(self.snapshot(),before)
    def test_stale_token_fails_without_mutation(self):
        self.make()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            other=self.mod.plan_import(self.store,'tablet',s)
            self.mod.apply_import(self.store,'tablet',s,expected_token=other['plan_token'],allow_plaintext=True)
            before=self.snapshot()
            with self.assertRaises(self.mod.ImportError):
                self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)
    def test_token_binds_source_and_policy(self):
        self.make()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            for source,policy in [('other','fail'),('phone','use-incoming')]:
                with self.assertRaises(self.mod.ImportError):
                    self.mod.apply_import(self.store,source,s,expected_token=p['plan_token'],policy=policy,allow_plaintext=True)
    def test_conflicts_fail_by_default(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='changed'; self.make(rows=rows,archive_id='a2')
        before=self.snapshot()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            self.assertEqual(p['conflicting_records'],1); self.assertFalse(p['can_apply'])
            with self.assertRaises(self.mod.ImportError):
                self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)
    def test_keep_local_retains_both_versions(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='changed'
        result=self.run_import(rows=rows,archive_id='a2',policy='keep-local')
        self.assertEqual(result['retained_conflicts'],1)
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],SECRET)
        with sqlite3.connect(self.store/'brain.sqlite3') as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM versions WHERE kind='note'").fetchone()[0],2)
    def test_use_incoming_is_explicit_and_retains_history(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='changed'
        result=self.run_import(rows=rows,archive_id='a2',policy='use-incoming')
        self.assertEqual(result['updated_records'],1)
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],'changed')
    def test_replaying_old_archive_does_not_undo_new_import(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='new'
        self.run_import(rows=rows,archive_id='a2',policy='use-incoming')
        self.run_import(policy='use-incoming')
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],'new')
    def test_archive_id_collision_is_rejected(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='different'; self.make(rows=rows)
        with self.stage.stage_archive(self.archive) as s, self.assertRaises(self.mod.ImportError):
            self.mod.plan_import(self.store,'phone',s)
    def test_absence_is_not_a_deletion(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl']=[]
        self.run_import(rows=rows,archive_id='a2')
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],SECRET)
    def test_two_sources_do_not_collide(self):
        self.run_import(); rows=records(); rows['notes/metadata.jsonl'][0]['body']='tablet'
        self.run_import(rows=rows,source='tablet')
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],SECRET)
        self.assertEqual(self.mod.get_record(self.store,'tablet','note','n1')['body'],'tablet')
    def test_all_profiles_and_encryption(self):
        for profile in ('BRAIN_ONLY','BRAIN_WITH_FILES','FULL_USER_BACKUP'):
            for enc in (False,True):
                with self.subTest(profile=profile,encrypted=enc):
                    self.run_import(source=f'{profile}-{enc}',profile=profile,encrypted=enc,archive_id=f'{profile}-{enc}')
    def test_malformed_dto_refused_before_store_change(self):
        before=self.snapshot()
        for field,value in [('confidence',True),('usageCount',2**63),('content',None),('id',''),('userConfirmed','true')]:
            rows=records(); rows['memory/memories.jsonl'][0][field]=value; self.make(rows=rows)
            with self.subTest(field=field), self.assertRaises(self.stage.RecordError):
                with self.stage.stage_archive(self.archive): pass
        self.assertEqual(self.snapshot(),before)
    def test_duplicate_record_ids_are_rejected(self):
        rows=records(); rows['notes/metadata.jsonl']*=2; self.make(rows=rows)
        with self.assertRaises(self.stage.RecordError):
            with self.stage.stage_archive(self.archive): pass
    def test_unresolved_links_are_counted_not_dropped(self):
        rows=records(); rows['conversations/turns.jsonl'][0]['conversationId']='missing'; self.make(rows=rows)
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            self.assertGreater(p['unresolved_references'],0)
            self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.mod.get_record(self.store,'phone','turn','t1')['conversationId'],'missing')
    def test_attachment_hash_and_size_mismatch(self):
        for field,value in [('sha256','0'*64),('sizeBytes',3)]:
            rows=records(); rows['files/index.jsonl'][0][field]=value; self.make(rows=rows)
            with self.subTest(field=field), self.assertRaises(self.stage.RecordError):
                with self.stage.stage_archive(self.archive): pass
    def test_source_change_after_staging_does_not_change_import(self):
        self.make()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s); self.archive.write_bytes(b'changed source')
            self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.mod.get_record(self.store,'phone','note','n1')['body'],SECRET)
    def test_injected_attachment_failure_rolls_back_entire_transaction(self):
        self.make(); before=self.snapshot()
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            with patch.object(self.mod,'_copy_entries',side_effect=OSError('disk-full-SECRET')):
                with self.assertRaises(OSError):
                    self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)
        self.assertEqual(self.mod.store_status(self.store)['archives'],0)
    def test_private_permissions_and_non_symlink_store(self):
        self.assertEqual(stat.S_IMODE(self.store.stat().st_mode),0o700)
        self.assertEqual(stat.S_IMODE((self.store/'brain.sqlite3').stat().st_mode),0o600)
        alias=self.root/'alias'; alias.symlink_to(self.store)
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(alias)
    def test_parent_symlink_and_hardlinked_database_rejected(self):
        alias=self.root/'alias'; alias.symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(alias/'store')
        os.link(self.store/'brain.sqlite3',self.root/'link.db')
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(self.store)
    def test_permissive_directory_rejected_not_chmodded(self):
        self.store.chmod(0o755)
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(self.store)
        self.assertEqual(stat.S_IMODE(self.store.stat().st_mode),0o755)
    def test_sidecar_symlink_rejected(self):
        (self.store/'brain.sqlite3-journal').symlink_to(self.root/'other')
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(self.store)
    def test_unknown_database_refused(self):
        db=self.store/'brain.sqlite3'; db.unlink()
        with sqlite3.connect(db) as conn: conn.execute('CREATE TABLE private(x)')
        db.chmod(0o600); before=db.read_bytes()
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(self.store)
        self.assertEqual(db.read_bytes(),before)
    def test_never_modifies_halo_database_or_schedules_anything(self):
        core=self.root/'halo.db'; core.write_bytes(b'untouched-core')
        self.run_import()
        self.assertEqual(core.read_bytes(),b'untouched-core')
        self.assertEqual(self.mod.get_record(self.store,'phone','agent-run','r1')['state'],'RUNNING')
        self.assertFalse(self.mod.store_status(self.store)['runtime_activation'])
    def test_staging_cleanup(self):
        self.make(); before=sorted(p.name for p in self.root.iterdir())
        with self.stage.stage_archive(self.archive,temp_dir=self.root): pass
        self.assertEqual(sorted(p.name for p in self.root.iterdir()),before)
    def test_record_limit(self):
        self.make()
        with self.assertRaises(self.stage.RecordError):
            with self.stage.stage_archive(self.archive,max_records=2): pass
    def test_wrong_password_has_no_persistent_effect(self):
        self.make(encrypted=True); before=self.snapshot()
        with self.assertRaises(Exception):
            with self.stage.stage_archive(self.archive,b'wrong'): pass
        self.assertEqual(before,self.snapshot())
    def test_unknown_fields_and_missing_defaults_survive(self):
        self.run_import()
        run=self.mod.get_record(self.store,'phone','agent-run','r1')
        self.assertNotIn('interactionId',run)
        self.assertEqual(run,records()['agent/runs.jsonl'][0])
    def test_cli_no_password_echo(self):
        p=subprocess.run([sys.executable,'-B',str(CLI),'--password','DO-NOT-ECHO'],capture_output=True)
        self.assertEqual(p.returncode,2); self.assertNotIn(b'DO-NOT-ECHO',p.stdout+p.stderr)
        self.assertNotIn(b'Traceback',p.stderr)
    def test_cli_workflow(self):
        self.make()
        cmd=[sys.executable,'-B',str(CLI)]
        p=subprocess.run(cmd+['plan',str(self.archive),'--store',str(self.store),'--source','phone'],capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr.decode()); plan=json.loads(p.stdout)
        p=subprocess.run(cmd+['apply',str(self.archive),'--store',str(self.store),'--source','phone',
            '--plan-token',plan['plan_token'],'--accept-plaintext'],capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr.decode()); self.assertNotIn(SECRET.encode(),p.stdout+p.stderr)
        p=subprocess.run(cmd+['read','--store',str(self.store),'--source','phone','--kind','note','--key','n1'],capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr.decode()); self.assertEqual(json.loads(p.stdout)['body'],SECRET)


    def test_del_control_in_identity_is_rejected(self):
        rows=records(); rows['notes/metadata.jsonl'][0]['objectId']='bad\x7fkey'; self.make(rows=rows)
        with self.assertRaises(self.stage.RecordError):
            with self.stage.stage_archive(self.archive): pass

    def test_recover_unknown_database_never_opens_sqlite(self):
        db=self.store/'brain.sqlite3'; db.unlink()
        with sqlite3.connect(db) as conn: conn.execute('CREATE TABLE not_halo(x)')
        db.chmod(0o600); before=db.read_bytes()
        with patch.object(self.mod,'_connect',side_effect=AssertionError('Unexpected SQLite open')):
            with self.assertRaises(self.mod.ImportError): self.mod.recover_store(self.store)
        self.assertEqual(db.read_bytes(),before)

    def test_plan_does_not_create_missing_store(self):
        self.make(); path=self.root/'not-initialized'
        with self.stage.stage_archive(self.archive) as staged, self.assertRaises(self.mod.ImportError):
            self.mod.plan_import(path,'phone',staged)
        self.assertFalse(path.exists())

    def test_init_refuses_nonempty_runtime_directory(self):
        runtime=self.root/'runtime'; runtime.mkdir(mode=0o700)
        (runtime/'halo.db').write_bytes(b'keep original')
        with self.assertRaises(self.mod.ImportError): self.mod.initialize_store(runtime)
        self.assertEqual(sorted(p.name for p in runtime.iterdir()),['halo.db'])
        self.assertEqual((runtime/'halo.db').read_bytes(),b'keep original')

    def test_init_is_idempotent(self):
        before=self.snapshot(); self.mod.initialize_store(self.store)
        self.assertEqual(self.snapshot(),before)

    def test_content_changed_between_preview_and_apply(self):
        self.make()
        with self.stage.stage_archive(self.archive) as a:
            token=self.mod.plan_import(self.store,'phone',a)['plan_token']
        rows=records(); rows['notes/metadata.jsonl'][0]['body']='new payload'
        self.make(rows=rows,archive_id='a2'); before=self.snapshot()
        with self.stage.stage_archive(self.archive) as b, self.assertRaises(self.mod.ImportError):
            self.mod.apply_import(self.store,'phone',b,expected_token=token,allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)

    def test_destination_identity_bound_to_token(self):
        self.make(); other=self.root/'other'; self.mod.initialize_store(other)
        with self.stage.stage_archive(self.archive) as a:
            token=self.mod.plan_import(self.store,'phone',a)['plan_token']
            with self.assertRaises(self.mod.ImportError):
                self.mod.apply_import(other,'phone',a,expected_token=token,allow_plaintext=True)
        self.assertEqual(self.mod.store_status(other)['archives'],0)

    def test_all_written_records_roll_back_on_late_failure(self):
        self.make(); before=self.snapshot(); original=self.mod._copy_records
        def interrupted(*args): original(*args); raise OSError('injected failure after rows')
        with self.stage.stage_archive(self.archive) as a:
            token=self.mod.plan_import(self.store,'phone',a)['plan_token']
            with patch.object(self.mod,'_copy_records',side_effect=interrupted), self.assertRaises(OSError):
                self.mod.apply_import(self.store,'phone',a,expected_token=token,allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)
        self.assertEqual(self.mod.store_status(self.store)['record_versions'],0)

    def test_process_lock_is_enforced(self):
        with self.mod._directory(self.store), self.assertRaises(self.mod.ImportError):
            self.mod.store_status(self.store)
        self.assertEqual(self.mod.store_status(self.store)['archives'],0)

    def test_hard_process_death_rolls_back_after_explicit_recovery(self):
        self.make(extra={'files/content/unused/payload.bin':os.urandom(8*1024*1024)})
        before=self.mod.store_status(self.store)
        code = """import os,sys
sys.path.insert(0,sys.argv[1])
import halo_brain_import as m
from halo_brain_records import stage_archive
original=m._copy_records
def die(*args):
    original(*args)
    os._exit(91)
m._copy_records=die
with stage_archive(sys.argv[2],temp_dir=sys.argv[4]) as s:
    p=m.plan_import(sys.argv[3],'phone',s)
    m.apply_import(sys.argv[3],'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
"""
        proc=subprocess.run([sys.executable,'-B','-c',code,str(ROOT/'scripts'),str(self.archive),str(self.store),str(self.root)],capture_output=True,timeout=30)
        self.assertEqual(proc.returncode,91,proc.stderr.decode())
        self.assertTrue((self.store/'brain.sqlite3-journal').exists())
        with self.assertRaises(self.mod.ImportError): self.mod.store_status(self.store)
        self.mod.recover_store(self.store)
        self.assertEqual(self.mod.store_status(self.store),before)
        self.assertFalse((self.store/'brain.sqlite3-journal').exists())

    def test_two_apply_processes_cannot_both_commit_same_preview(self):
        self.make()
        with self.stage.stage_archive(self.archive) as s:
            token=self.mod.plan_import(self.store,'phone',s)['plan_token']
        command=[sys.executable,'-B',str(CLI),'apply',str(self.archive),'--store',str(self.store),
            '--source','phone','--plan-token',token,'--accept-plaintext']
        a=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        b=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            a.communicate(timeout=15); b.communicate(timeout=15)
            self.assertEqual(sorted([a.returncode,b.returncode]),[0,2])
        finally:
            for child in (a,b):
                if child.poll() is None: child.kill(); child.communicate()
        self.assertEqual(self.mod.store_status(self.store)['archives'],1)

    def test_owned_file_cannot_reference_arbitrary_path(self):
        rows=records(); rows['files/index.jsonl'][0]['ownedCopyPath']='archive:../../secret'
        self.make(rows=rows)
        with self.assertRaises(self.stage.RecordError):
            with self.stage.stage_archive(self.archive): pass

    def test_external_references_remain_opaque(self):
        rows=records(); rows['files/index.jsonl'][0].update(ownership='EXTERNAL_REFERENCE',
            ownedCopyPath='/etc/shadow',uri='https://example.invalid/do-not-fetch')
        self.run_import(rows=rows)
        self.assertEqual(self.mod.get_record(self.store,'phone','file','n1')['ownedCopyPath'],'/etc/shadow')

    def test_invalid_settings_map_is_refused(self):
        self.make(extra={'settings/user-settings.json':b'{"values":{"locale":true},"providerConfiguration":{}}'})
        with self.assertRaises(self.stage.RecordError):
            with self.stage.stage_archive(self.archive): pass

    def test_record_semantic_comparison_ignores_key_order_and_spacing(self):
        self.run_import()
        row=records()['notes/metadata.jsonl'][0]
        self.make(archive_id='a2',extra={'notes/metadata.jsonl':json.dumps(dict(reversed(list(row.items()))),indent=None).encode()+b'\n'})
        with self.stage.stage_archive(self.archive) as s:
            p=self.mod.plan_import(self.store,'phone',s)
            self.assertEqual(p['conflicting_records'],0)
            self.mod.apply_import(self.store,'phone',s,expected_token=p['plan_token'],allow_plaintext=True)
        self.assertEqual(self.mod.store_status(self.store)['archives'],2)

    def test_cli_encrypted_stdin_and_fixed_errors(self):
        self.make(encrypted=True)
        cmd=[sys.executable,'-B',str(CLI),'plan',str(self.archive),'--store',str(self.store),'--source','phone','--password-stdin']
        proc=subprocess.run(cmd,input='pass-✓\n'.encode(),capture_output=True,timeout=15)
        self.assertEqual(proc.returncode,0,proc.stderr.decode())
        self.assertNotIn('pass-✓'.encode(),proc.stdout+proc.stderr)
        bad=subprocess.run(cmd,input=b'bad-private-password\n',capture_output=True,timeout=15)
        self.assertEqual(bad.returncode,2)
        self.assertNotIn(b'bad-private-password',bad.stdout+bad.stderr)
        self.assertNotIn(b'Traceback',bad.stderr)

    def test_cli_read_escapes_terminal_controls(self):
        rows=records(); rows['notes/metadata.jsonl'][0]['body']='\x1b[31mtext\nmore'
        self.run_import(rows=rows)
        cmd=[sys.executable,'-B',str(CLI),'read','--store',str(self.store),'--source','phone','--kind','note','--key','n1']
        p=subprocess.run(cmd,capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr.decode()); self.assertNotIn(b'\x1b',p.stdout)
        self.assertEqual(json.loads(p.stdout)['body'],rows['notes/metadata.jsonl'][0]['body'])


    def test_explicit_record_listing_is_bounded_and_paginated(self):
        rows=records(); rows['notes/metadata.jsonl'].append({'objectId':'n2','body':'second','revision':1})
        self.run_import(rows=rows)
        a=self.mod.list_records(self.store,'phone','note',limit=1)
        b=self.mod.list_records(self.store,'phone','note',limit=1,offset=a['next_offset'])
        self.assertEqual(a['keys'],['n1']); self.assertEqual(b['keys'],['n2'])
        self.assertIsNone(b['next_offset']); self.assertNotIn(SECRET,json.dumps(a))
        with self.assertRaises(self.mod.ImportError): self.mod.list_records(self.store,'phone','note',limit=10000)

    def test_cli_list_is_explicit_and_does_not_emit_bodies(self):
        self.run_import()
        p=subprocess.run([sys.executable,'-B',str(CLI),'list','--store',str(self.store),'--source','phone','--kind','note'],capture_output=True)
        self.assertEqual(p.returncode,0,p.stderr.decode()); self.assertEqual(json.loads(p.stdout)['keys'],['n1'])
        self.assertNotIn(SECRET.encode(),p.stdout+p.stderr)


    def test_staging_database_is_private(self):
        self.make()
        with self.stage.stage_archive(self.archive,temp_dir=self.root) as staged:
            self.assertEqual(stat.S_IMODE(staged.path.parent.stat().st_mode),0o700)
            self.assertEqual(stat.S_IMODE(staged.path.stat().st_mode),0o600)

    def test_import_covers_every_supported_record_path(self):
        from halo_brain_archive import RECORD_PATHS
        self.assertEqual(set(self.stage.SPECS),set(RECORD_PATHS)|{'settings/user-settings.json'})

    def test_whitespace_json_is_preserved_exactly_in_version_payload(self):
        raw=b'{ "revision" : 1, "body" : "literal", "objectId" : "n1" }\n'
        self.make(extra={'notes/metadata.jsonl':raw})
        with self.stage.stage_archive(self.archive) as staged:
            plan=self.mod.plan_import(self.store,'phone',staged)
            self.mod.apply_import(self.store,'phone',staged,expected_token=plan['plan_token'],allow_plaintext=True)
        with sqlite3.connect(self.store/'brain.sqlite3') as db:
            self.assertEqual(db.execute("SELECT payload FROM versions WHERE kind='note'").fetchone()[0],raw)


    def test_staged_record_corruption_is_caught_before_commit(self):
        from halo_brain_archive import ArchiveError
        self.make(); before=self.snapshot()
        with self.stage.stage_archive(self.archive) as staged:
            plan=self.mod.plan_import(self.store,'phone',staged)
            staged.connection.execute('PRAGMA query_only=OFF')
            staged.connection.execute("UPDATE records SET payload=? WHERE kind='note'",
                (b'{"objectId":"n1","body":"corrupt","revision":1}\n',))
            staged.connection.execute('PRAGMA query_only=ON')
            with self.assertRaises(ArchiveError):
                self.mod.apply_import(self.store,'phone',staged,expected_token=plan['plan_token'],allow_plaintext=True)
        self.assertEqual(self.snapshot(),before)


if __name__ == '__main__': unittest.main(verbosity=2)
