"""Executable, self-contained Studio schema 3 compatibility tests."""
import json
from pathlib import Path
import sqlite3
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from halo_brain_studio import StudioContractError, document, session, verify_stage


def doc():
    return dict(schema=1, width=1024, height=768, background=0, selected='layer1',
        recipe=dict(turns=0, mirror=False, square=False, edge=2048, format='PNG', crop=None),
        layers=[dict(id='layer1', name='Original', kind='IMAGE', bounds=dict(x=0,y=0,width=1000,height=1000),
            visible=True, locked=False, opacity=1000, rotation=0, color=-1, text='', fontSize=80,
            strokeWidth=5, ink='', source=dict(id='img-1',version='sha'), mask=None,
            brightness=0, contrast=1000, saturation=1000)])


def js(value): return json.dumps(value, ensure_ascii=False, separators=(',',':'))


def sess(): return js(dict(schema=1,current=js(doc()), undo=[],redo=[]))


def stage():
    db=sqlite3.connect(':memory:')
    db.execute('CREATE TABLE records(kind TEXT, key TEXT, payload BLOB, PRIMARY KEY(kind,key))')
    header=dict(id='studio1', type='ARTIFACT', title='My Studio', contentRef='studio:studio1')
    db.execute('INSERT INTO records VALUES (?,?,?)', ('workspace','studio1',js(header).encode()))
    db.execute('INSERT INTO records VALUES (?,?,?)', ('studio-project','studio1',js(dict(objectId='studio1',revision=3,committed=js(doc()),session=sess())).encode()))
    db.execute('INSERT INTO records VALUES (?,?,?)', ('studio-version','studio1:1',js(dict(objectId='studio1',revision=1,document=js(doc()),savedAt=10)).encode()))
    db.execute('INSERT INTO records VALUES (?,?,?)', ('studio-version','studio1:3',js(dict(objectId='studio1',revision=3,document=js(doc()),savedAt=11)).encode()))
    return db


class StudioTests(unittest.TestCase):
    def makeStage(self):
        db=stage()
        self.addCleanup(db.close)
        return db

    def assertRejected(self, value):
        with self.assertRaises((StudioContractError,ValueError,TypeError,KeyError)):
            document(js(value) if not isinstance(value,str) else value)

    def test_valid_document(self): document(js(doc()))
    def test_valid_session(self): session(sess())
    def test_empty_document(self):
        v=doc();v['selected']=None;v['layers']=[];document(js(v))
    def test_nested_image_reference_remains_inert(self): document(js(doc()))
    def test_future_document_version(self):
        v=doc();v['schema']=2;self.assertRejected(v)
    def test_additional_root_field(self):
        v=doc();v['evil']='instruction';self.assertRejected(v)
    def test_duplicate_json_keys(self):
        v=js(doc());self.assertRejected(v[:-1]+',"schema":1}')
    def test_negative_size(self):
        v=doc();v['width']=0;self.assertRejected(v)
    def test_oversize_area(self):
        v=doc();v['width']=4096;v['height']=4096;self.assertRejected(v)
    def test_boolean_forgery(self):
        v=doc();v['width']=True;self.assertRejected(v)
    def test_bad_crop_square(self):
        v=doc();v['recipe']['square']=True;v['recipe']['crop']=dict(x=0,y=0,width=50,height=50);self.assertRejected(v)
    def test_invalid_crop_bounds(self):
        v=doc();v['recipe']['crop']=dict(x=900,y=0,width=200,height=500);self.assertRejected(v)
    def test_unsupported_output_format(self):
        v=doc();v['recipe']['format']='WEBP';self.assertRejected(v)
    def test_invalid_recipe_turns(self):
        v=doc();v['recipe']['turns']=4;self.assertRejected(v)
    def test_duplicate_layers(self):
        v=doc();v['layers'].append(dict(v['layers'][0]));self.assertRejected(v)
    def test_unselected_layer(self):
        v=doc();v['selected']='notpresent';self.assertRejected(v)
    def test_bad_layer_id(self):
        v=doc();v['layers'][0]['id']='../../secret';self.assertRejected(v)
    def test_source_required_for_image(self):
        v=doc();v['layers'][0]['source']=None;self.assertRejected(v)
    def test_source_for_text_disallowed(self):
        v=doc();v['layers'][0]['kind']='TEXT';self.assertRejected(v)
    def test_unicode_text(self):
        v=doc();v['layers'][0]['kind']='TEXT';v['layers'][0]['source']=None;v['layers'][0]['text']='Привет 🌍';document(js(v))
    def test_invalid_text_on_rectangle(self):
        v=doc();v['layers'][0]['kind']='RECTANGLE';v['layers'][0]['source']=None;v['layers'][0]['text']='bad';self.assertRejected(v)
    def test_future_session_schema(self):
        v=json.loads(sess());v['schema']=2
        with self.assertRaises(StudioContractError): session(js(v))
    def test_session_history_limit(self):
        v=json.loads(sess());v['undo']=[js(doc())]*17
        with self.assertRaises(StudioContractError): session(js(v))
    def test_session_rejects_missing_field(self):
        v=json.loads(sess());del v['redo']
        with self.assertRaises(StudioContractError): session(js(v))
    def test_session_invalid_embedded_doc(self):
        v=json.loads(sess());v['undo']=['{"schema":99}']
        with self.assertRaises(StudioContractError): session(js(v))
    def test_stage_schema3(self): verify_stage(self.makeStage(),3)
    def test_stage2_forbids_studio_records(self):
        with self.assertRaises(StudioContractError):verify_stage(self.makeStage(),2)
    def test_empty_stage3(self):
        db=sqlite3.connect(':memory:');self.addCleanup(db.close);db.execute('CREATE TABLE records(kind TEXT,key TEXT,payload BLOB)');verify_stage(db,3)
    def test_missing_project_workspace(self):
        db=self.makeStage();db.execute("DELETE FROM records WHERE kind='workspace'")
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_missing_project_for_version(self):
        db=self.makeStage();db.execute("DELETE FROM records WHERE kind='studio-project'")
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_future_saved_revision(self):
        db=self.makeStage();db.execute("UPDATE records SET key='studio1:4',payload=? WHERE kind='studio-version' AND key='studio1:3'", (js(dict(objectId='studio1',revision=4,document=js(doc()),savedAt=11)).encode(),))
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_mismatched_composite_identity(self):
        db=self.makeStage();db.execute("UPDATE records SET key='wrong' WHERE kind='studio-version' AND key='studio1:3'")
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_invalid_project_header_type(self):
        db=self.makeStage();h=dict(id='studio1',type='NOTE',title='Title',contentRef='studio:studio1');db.execute('UPDATE records SET payload=? WHERE kind=\'workspace\'', (js(h).encode(),))
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_unmatched_studio_workspace(self):
        db=self.makeStage();h=dict(id='studio2',type='ARTIFACT',title='T',contentRef='studio:studio2');db.execute('INSERT INTO records VALUES (?,?,?)',('workspace','studio2',js(h).encode()))
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_bad_project_revision(self):
        db=self.makeStage();v=dict(objectId='studio1',revision=0,committed=js(doc()),session=sess());db.execute("UPDATE records SET payload=? WHERE kind='studio-project'",(js(v).encode(),))
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_invalid_negative_saved_at(self):
        db=self.makeStage();v=dict(objectId='studio1',revision=1,document=js(doc()),savedAt=-1);db.execute("UPDATE records SET payload=? WHERE kind='studio-version' AND key='studio1:1'",(js(v).encode(),))
        with self.assertRaises(StudioContractError):verify_stage(db,3)
    def test_unknown_stage_version(self):
        with self.assertRaises(StudioContractError):verify_stage(self.makeStage(),4)


if __name__=='__main__':unittest.main()
