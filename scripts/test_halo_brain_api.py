import http.client
from contextlib import closing
import importlib
import json
import os
from pathlib import Path
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import halo_brain_import as importer
from halo_brain_reader import BrainReader
from brain_api_test_support import memory, import_records


def wire(path='/v1/status', payload=b'{}', *, extra=b'', method=b'POST', length=None):
    return method + b' ' + path.encode() + b' HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(payload) if length is None else length).encode() + b'\r\n' + extra + b'\r\n' + payload


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='hapi-'); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name); self.store=self.root/'store'; self.runtime=self.root/'run'
        self.runtime.mkdir(mode=0o700)
        importer.initialize_store(self.store)
        import_records(self.root,self.store,{'memory':[memory()]})
        if not (Path(__file__).parent/'halo_brain_api.py').exists(): self.fail('Unix Brain API server not implemented')
        self.module=importlib.import_module('halo_brain_api')
        self.reader=BrainReader(self.store,sources=['phone'])
        self.server=self.module.ApiServer(self.reader,self.runtime, request_timeout=0.3)
        self.server.__enter__(); self.stop=threading.Event()
        self.thread=threading.Thread(target=self.server.serve,args=(self.stop,),daemon=True); self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.stop.set(); self.thread.join(3); self.server.__exit__(None,None,None)
        self.assertFalse(self.thread.is_alive())

    def exchange(self, data, *, pause=0):
        with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as s:
            s.settimeout(2); s.connect(str(self.server.path)); s.sendall(data)
            if pause: time.sleep(pause)
            parts=[]
            while True:
                block=s.recv(65536)
                if not block: break
                parts.append(block)
        return b''.join(parts)

    def response(self, data):
        raw=self.exchange(data); headers,body=raw.split(b'\r\n\r\n',1)
        self.assertIn(b'Cache-Control: no-store',headers)
        self.assertIn(b'Connection: close',headers)
        return int(headers.split()[1]), json.loads(body)

    def test_status_over_real_unix_socket(self):
        status,b=self.response(wire()); self.assertEqual(status,200); self.assertTrue(b['ok'])
    def test_actual_http_client(self):
        class UnixConnection(http.client.HTTPConnection):
            def connect(conn):
                conn.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); conn.sock.settimeout(2)
                conn.sock.connect(str(self.server.path))
        with closing(UnixConnection('localhost')) as c:
            c.request('POST','/v1/status',body=b'{}',headers={'Content-Type':'application/json'})
            r=c.getresponse(); self.assertEqual(r.status,200); self.assertTrue(json.loads(r.read())['ok'])
    def test_socket_not_tcp_and_private(self):
        self.assertEqual(self.server.listener.family,socket.AF_UNIX)
        self.assertEqual(stat.S_IMODE(self.server.path.stat().st_mode),0o600)
    def test_query_body_not_in_url(self):
        q=json.dumps(dict(source='phone',scope='GLOBAL',query='celsius')).encode()
        status,b=self.response(wire('/v1/memory/search',q)); self.assertEqual(status,200)
        self.assertEqual(len(b['result']['items']),1)
    def test_raw_disabled_default(self):
        q=json.dumps(dict(source='phone',kind='memory',key='m1',intent='inspect')).encode()
        self.assertEqual(self.response(wire('/v1/records/read',q))[0],403)
    def test_unknown_path(self): self.assertEqual(self.response(wire('/v1/delete'))[0],404)
    def test_get_not_supported(self): self.assertEqual(self.response(wire(method=b'GET'))[0],405)
    def test_query_string_rejected(self): self.assertEqual(self.response(wire('/v1/status?secret=value'))[0],404)
    def test_duplicate_length_rejected(self): self.assertEqual(self.response(wire(extra=b'Content-Length: 2\r\n'))[0],400)
    def test_chunked_rejected(self): self.assertEqual(self.response(wire(extra=b'Transfer-Encoding: chunked\r\n'))[0],400)
    def test_origin_rejected(self): self.assertEqual(self.response(wire(extra=b'Origin: https://example.org\r\n'))[0],403)
    def test_expect_rejected(self): self.assertEqual(self.response(wire(extra=b'Expect: 100-continue\r\n'))[0],400)
    def test_oversized_body_rejected_before_read(self): self.assertEqual(self.response(wire(length=99999))[0],413)
    def test_negative_length(self): self.assertEqual(self.response(wire(length=-1))[0],400)
    def test_no_content_length(self):
        self.assertEqual(self.response(b'POST /v1/status HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n\r\n{}')[0],400)
    def test_wrong_host(self): self.assertEqual(self.response(wire().replace(b'Host: localhost',b'Host: remote'))[0],400)
    def test_wrong_content_type(self): self.assertEqual(self.response(wire().replace(b'application/json',b'text/plain'))[0],415)
    def test_invalid_json(self): self.assertEqual(self.response(wire(payload=b'{bad'))[0],400)
    def test_duplicate_json_keys(self): self.assertEqual(self.response(wire(payload=b'{"x":1,"x":2}'))[0],400)
    def test_json_array_refused(self): self.assertEqual(self.response(wire(payload=b'[]'))[0],400)
    def test_deep_json_refused(self): self.assertEqual(self.response(wire(payload=b'{"x":'+b'['*30+b'0'+b']'*30+b'}'))[0],400)
    def test_nonfinite_json_refused(self): self.assertEqual(self.response(wire(payload=b'{"x":NaN}'))[0],400)
    def test_invalid_utf8_refused(self): self.assertEqual(self.response(wire(payload=b'{"x":"\xff"}'))[0],400)
    def test_obs_fold_refused(self): self.assertEqual(self.response(wire(extra=b' Folded: value\r\n'))[0],400)
    def test_large_header_refused(self): self.assertEqual(self.response(wire(extra=b'X-Large: '+b'a'*8500+b'\r\n'))[0],413)
    def test_body_timeout(self):
        status,b=self.response(wire(payload=b'',length=100))
        self.assertEqual(status,408)
    def test_peer_credential_denial(self):
        with patch.object(self.module,'peer_uid',return_value=os.getuid()+1):
            # Server rejects before reading. Use a no-payload connection.
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as s:
                s.settimeout(2); s.connect(str(self.server.path)); data=s.recv(4096)
            self.assertIn(b'403',data)
    def test_error_does_not_echo_input(self):
        raw=self.exchange(wire('/v1/private-SECRET'))
        self.assertNotIn(b'SECRET',raw); self.assertNotIn(str(self.store).encode(),raw)
    def test_second_server_does_not_unlink_live_socket(self):
        before=self.server.path.stat().st_ino
        with self.assertRaises(Exception):
            with self.module.ApiServer(self.reader,self.runtime): pass
        self.assertEqual(self.server.path.stat().st_ino,before)
        self.assertEqual(self.response(wire())[0],200)
    def test_cleanup_never_deletes_replacement_file(self):
        self.server.path.unlink(); self.server.path.write_text('keep')
        self.stop.set(); self.thread.join(2); self.server.__exit__(None,None,None)
        self.assertEqual(self.server.path.read_text(),'keep')
    def test_store_remains_read_only(self):
        p=self.store/importer.DATABASE; original=p.read_bytes()
        self.response(wire()); self.assertEqual(p.read_bytes(),original)
    def test_secure_source_settings_not_in_response(self):
        status,b=self.response(wire()); self.assertEqual(status,200)
        self.assertNotIn(str(self.store),json.dumps(b))
    def test_sdk_client(self):
        if not (Path(__file__).parent/'halo_brain_client.py').exists(): self.fail('Unix client not implemented')
        c=importlib.import_module('halo_brain_client').BrainClient(self.server.path)
        self.assertEqual(c.request('/v1/status',{})['api_version'],1)
        with self.assertRaises(importlib.import_module('halo_brain_client').ClientError):
            c.request('/v1/records/read',dict(source='phone',kind='memory',key='m1',intent='inspect'))


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='hapi-'); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name); self.store=self.root/'store'; importer.initialize_store(self.store)
        if not (Path(__file__).parent/'halo_brain_api.py').exists(): self.fail('Unix Brain API server not implemented')
        self.module=importlib.import_module('halo_brain_api'); self.reader=BrainReader(self.store,sources=['phone'])
    def test_insecure_runtime_directory_refused(self):
        p=self.root/'run'; p.mkdir(mode=0o755)
        with self.assertRaises(Exception):
            with self.module.ApiServer(self.reader,p): pass
    def test_symlink_runtime_directory_refused(self):
        p=self.root/'real'; p.mkdir(mode=0o700); link=self.root/'link'; link.symlink_to(p)
        with self.assertRaises(Exception):
            with self.module.ApiServer(self.reader,link): pass
    def test_existing_socket_path_preserved(self):
        p=self.root/'run'; p.mkdir(mode=0o700); f=p/'brain-api.sock'; f.write_text('keep')
        with self.assertRaises(Exception):
            with self.module.ApiServer(self.reader,p): pass
        self.assertEqual(f.read_text(),'keep')
    def test_runtime_store_same_directory_refused(self):
        with self.assertRaises(Exception):
            with self.module.ApiServer(self.reader,self.store): pass
        self.assertEqual(os.listdir(self.store),[importer.DATABASE])
    def test_cli_terminates_and_restarts_cleanly(self):
        p=self.root/'run'; p.mkdir(mode=0o700)
        script=Path(__file__).parent/'halo_brain_api.py'
        for _ in range(2):
            process=subprocess.Popen([sys.executable,'-B',str(script),'--store',str(self.store),
                '--runtime-dir',str(p),'--source','phone'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                for step in range(100):
                    if (p/'brain-api.sock').exists() or process.poll() is not None: break
                    time.sleep(.02)
                self.assertTrue((p/'brain-api.sock').exists())
                process.send_signal(signal.SIGTERM)
                stdout,stderr=process.communicate(timeout=5)
                self.assertEqual(process.returncode,0,(stdout,stderr))
                self.assertFalse((p/'brain-api.sock').exists())
                self.assertNotIn(str(self.store).encode(),stdout+stderr)
            finally:
                if process.poll() is None: process.kill(); process.communicate()


if __name__=='__main__': unittest.main(verbosity=2)
