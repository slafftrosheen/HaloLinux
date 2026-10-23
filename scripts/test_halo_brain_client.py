from contextlib import contextmanager
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest

from halo_brain_client import BrainClient, ClientError


class ClientTests(unittest.TestCase):
    @contextmanager
    def response(self, obj, *, status=200, extra=b'', raw_body=None):
        with tempfile.TemporaryDirectory(prefix='hclient-') as root:
            path=Path(root)/'api.sock'
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as server:
                server.bind(str(path)); server.listen(1); server.settimeout(2)
                raw=json.dumps(obj).encode() if raw_body is None else raw_body
                payload=f'HTTP/1.1 {status} Test\r\nContent-Length: {len(raw)}\r\nContent-Type: application/json; charset=utf-8\r\n'.encode()+extra+b'\r\n'+raw
                def worker():
                    connection,_=server.accept()
                    with connection:
                        connection.recv(65536)
                        try: connection.sendall(payload)
                        except OSError: pass
                t=threading.Thread(target=worker,daemon=True);t.start()
                try: yield BrainClient(path)
                finally: t.join(3)

    def test_success(self):
        with self.response(dict(api_version=1,ok=True,result={})) as c:
            self.assertEqual(c.request('/v1/status',{})['result'],{})
    def test_non_object_error_does_not_raise_attribute_error(self):
        with self.response(dict(api_version=1,ok=False,error='PRIVATE-DO-NOT-ECHO'),status=503) as c:
            with self.assertRaises(ClientError) as exc: c.request('/v1/status',{})
            self.assertNotIn('PRIVATE',str(exc.exception))
    def test_null_error_does_not_raise_attribute_error(self):
        with self.response(dict(api_version=1,ok=False,error=None),status=503) as c:
            with self.assertRaises(ClientError): c.request('/v1/status',{})
    def test_unknown_error_code_is_not_echoed(self):
        with self.response(dict(api_version=1,ok=False,error={'code':'PRIVATE'}),status=503) as c:
            with self.assertRaises(ClientError) as exc: c.request('/v1/status',{})
            self.assertEqual(exc.exception.code,'request_failed')
    def test_known_error_code_retained(self):
        with self.response(dict(api_version=1,ok=False,error={'code':'cursor_mismatch'}),status=409) as c:
            with self.assertRaises(ClientError) as exc: c.request('/v1/status',{})
            self.assertEqual((exc.exception.code,exc.exception.status),('cursor_mismatch',409))
    def test_nonfinite_response_is_not_returned(self):
        with self.response(dict(api_version=1,ok=True,result={'value':float('inf')})) as c:
            with self.assertRaises(ClientError): c.request('/v1/status',{})
    def test_overflowing_exponent_response_is_not_returned(self):
        with self.response({},raw_body=b'{"api_version":1,"ok":true,"result":{"value":1e999}}') as c:
            with self.assertRaises(ClientError): c.request('/v1/status',{})
    def test_response_version_bool_rejected(self):
        with self.response(dict(api_version=True,ok=True,result={})) as c:
            with self.assertRaises(ClientError): c.request('/v1/status',{})
    def test_response_result_must_be_object(self):
        with self.response(dict(api_version=1,ok=True,result=[])) as c:
            with self.assertRaises(ClientError): c.request('/v1/status',{})
    def test_invalid_timeout_configuration(self):
        for timeout in (True,'5',0,-1,31,float('inf'),float('nan')):
            with self.subTest(timeout=timeout),self.assertRaises(ClientError): BrainClient('/tmp/no.sock',timeout=timeout)
    def test_invalid_endpoint_type(self):
        with self.assertRaises(ClientError): BrainClient('/tmp/no.sock').request([], {})
    def test_oversized_request_rejected_locally(self):
        with self.assertRaises(ClientError) as exc: BrainClient('/tmp/no.sock').request('/v1/status',{'x':'a'*17000})
        self.assertEqual(exc.exception.code,'request_too_large')
    def test_no_tcp_fallback(self):
        with self.assertRaises(ClientError): BrainClient('http://localhost:8080')


if __name__=='__main__': unittest.main(verbosity=2)
