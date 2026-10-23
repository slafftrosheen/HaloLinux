"""Contract drift checks; stdlib only. Full schema validation is a separate check."""
import json
from pathlib import Path
import tempfile
import unittest

import halo_brain_api as api
from halo_brain_client import ENDPOINTS
from halo_brain_reader import BrainReader, SCOPES, MEMORY_KINDS, ROUTES
from halo_brain_records import KINDS
import halo_brain_import as importer
from brain_api_test_support import memory, import_records


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.document=json.loads((Path(__file__).resolve().parents[1]/'protocol/schemas/brain-api-v1.openapi.json').read_text())

    def test_routes_and_transport_limits(self):
        self.assertEqual(set(self.document['paths']),ROUTES)
        self.assertEqual(ROUTES,ENDPOINTS)
        limits=self.document['x-transport']
        self.assertEqual(limits['maxRequestBytes'],api.MAX_REQUEST)
        self.assertEqual(limits['maxResponseBytes'],api.MAX_RESPONSE)
        self.assertEqual(limits['maxHeaderBytes'],api.MAX_HEADERS)
        self.assertEqual(limits['requestReadDeadlineSeconds'],api.REQUEST_SECONDS)
        self.assertFalse(limits['tcpFallback'])

    def test_enums_track_reader_and_record_contract(self):
        s=self.document['components']['schemas']
        self.assertEqual(set(s['ListRequest']['properties']['kind']['enum']),KINDS)
        self.assertEqual(set(s['MemorySearchRequest']['properties']['scope']['enum']),SCOPES)
        self.assertEqual(set(s['Candidate']['properties']['kind']['enum']),MEMORY_KINDS)

    def test_all_local_references_resolve(self):
        def walk(value):
            if isinstance(value,dict):
                if '$ref' in value:
                    self.assertTrue(value['$ref'].startswith('#/'))
                    resolved=self.document
                    for part in value['$ref'][2:].split('/'): resolved=resolved[part]
                for item in value.values():walk(item)
            elif isinstance(value,list):
                for item in value:walk(item)
        walk(self.document)

    def test_all_request_examples_execute(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);store=root/'store';importer.initialize_store(store)
            import_records(root,store,{'memory':[memory()]})
            reader=BrainReader(store,sources=['phone'],allow_inspection=True)
            for path,item in self.document['paths'].items():
                example=item['post']['requestBody']['content']['application/json']['example']
                status,body=reader.dispatch(path,example)
                with self.subTest(path=path):
                    self.assertEqual(status,200,body)
                    self.assertEqual(body['api_version'],1)
                    self.assertIn('snapshot',body['result'])


if __name__=='__main__': unittest.main(verbosity=2)
