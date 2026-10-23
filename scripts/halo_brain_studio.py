#!/usr/bin/env python3
"""Structural validation for inert Android Studio archive records (schema 3).

This mirrors reviewed Studio document/session bounds, but cannot validate Android
InkCodec internals. Source files/URIs are never opened or rendered here.
"""
from __future__ import annotations

import json
import re
import sqlite3

ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
MAX_REVISION = 9_007_199_254_740_991
LAYER_KEYS = frozenset(('id', 'name', 'kind', 'bounds', 'visible', 'locked', 'opacity',
    'rotation', 'color', 'text', 'fontSize', 'strokeWidth', 'ink', 'source', 'mask',
    'brightness', 'contrast', 'saturation'))
RECT_KEYS = frozenset(('x', 'y', 'width', 'height'))
RECIPE_KEYS = frozenset(('turns', 'mirror', 'square', 'edge', 'format', 'crop'))
DOCUMENT_KEYS = frozenset(('schema', 'width', 'height', 'background', 'selected', 'recipe', 'layers'))
SESSION_KEYS = frozenset(('schema', 'current', 'undo', 'redo'))
KINDS = frozenset(('IMAGE', 'INK', 'TEXT', 'RECTANGLE', 'ELLIPSE'))


class StudioContractError(ValueError):
    """Fixed diagnostic; never include private Studio content."""


def need(value: bool) -> None:
    if not value:
        raise StudioContractError('Unsupported or malformed Studio archive record.')


def integer(value, low, high):
    need(type(value) is int and low <= value <= high)
    return value


def text(value, max_length: int, min_length: int = 0):
    need(isinstance(value, str) and min_length <= len(value) <= max_length)
    return value


def _object(value, expected):
    need(type(value) is dict and value.keys() == expected)
    return value


def _pairs(items):
    result = {}
    for key, value in items:
        need(key not in result)
        result[key] = value
    return result


def _invalid_constant(_):
    raise StudioContractError('Unsupported Studio number.')


def parse(value: str, limit: int):
    text(value, limit, 2)
    try:
        parsed = json.loads(value, object_pairs_hook=_pairs, parse_constant=_invalid_constant)
    except (ValueError, TypeError, RecursionError):
        raise StudioContractError('Unsupported or malformed Studio JSON.') from None
    return parsed


def _rect(value, crop: bool = False):
    rect = _object(value, RECT_KEYS)
    x = integer(rect['x'], 0 if crop else -1000, 999 if crop else 2000)
    y = integer(rect['y'], 0 if crop else -1000, 999 if crop else 2000)
    width = integer(rect['width'], 1, 1000 if crop else 3000)
    height = integer(rect['height'], 1, 1000 if crop else 3000)
    if crop:
        need(x + width <= 1000 and y + height <= 1000)


def document(encoded: str) -> None:
    doc = _object(parse(encoded, 2_000_000), DOCUMENT_KEYS)
    need(integer(doc['schema'], 1, 1) == 1)
    width = integer(doc['width'], 1, 4096)
    height = integer(doc['height'], 1, 4096)
    need(width * height <= 12_000_000)
    integer(doc['background'], -(2**31), 2**31-1)
    selected = doc['selected']
    need(selected is None or (isinstance(selected, str) and ID.fullmatch(selected) is not None))
    recipe = _object(doc['recipe'], RECIPE_KEYS)
    integer(recipe['turns'], 0, 3)
    need(type(recipe['mirror']) is bool and type(recipe['square']) is bool)
    need(type(recipe['edge']) is int and recipe['edge'] in (512, 1024, 2048, 4096))
    need(recipe['format'] in ('PNG', 'JPEG'))
    need(recipe['crop'] is None or not recipe['square'])
    if recipe['crop'] is not None:
        _rect(recipe['crop'], crop=True)
    layers = doc['layers']
    need(type(layers) is list and len(layers) <= 32)
    used = set()
    for layer in layers:
        layer = _object(layer, LAYER_KEYS)
        identifier = text(layer['id'], 100, 1)
        need(ID.fullmatch(identifier) is not None and identifier not in used)
        used.add(identifier)
        need(text(layer['name'], 120, 1).strip() != '')
        kind = layer['kind']
        need(type(kind) is str and kind in KINDS)
        _rect(layer['bounds'])
        if layer['mask'] is not None:
            _rect(layer['mask'])
        need(type(layer['visible']) is bool and type(layer['locked']) is bool)
        integer(layer['opacity'], 0, 1000)
        integer(layer['rotation'], -360, 360)
        integer(layer['color'], -(2**31), 2**31-1)
        text(layer['text'], 2000)
        integer(layer['fontSize'], 1, 1000)
        integer(layer['strokeWidth'], 1, 100)
        text(layer['ink'], 2_000_000)
        integer(layer['brightness'], -1000, 1000)
        integer(layer['contrast'], 0, 3000)
        integer(layer['saturation'], 0, 3000)
        need(kind == 'TEXT' or layer['text'] == '')
        need(kind == 'INK' or layer['ink'] == '')
        source = layer['source']
        if kind == 'IMAGE':
            src = _object(source, frozenset(('id', 'version')))
            need(ID.fullmatch(text(src['id'], 100, 1)) is not None)
            text(src['version'], 200, 1)
        else:
            need(source is None)
    need(selected is None or selected in used)
    # INK payloads are preserved but not decoded into executable model commands.


def session(encoded: str) -> None:
    root = _object(parse(encoded, 9_000_000), SESSION_KEYS)
    integer(root['schema'], 1, 1)
    document(root['current'])
    history_size = 0
    for key in ('undo', 'redo'):
        values = root[key]
        need(type(values) is list and len(values) <= 16)
        for snapshot in values:
            document(snapshot)
            history_size += len(snapshot)
            need(history_size <= 4_000_000)


def verify_stage(db: sqlite3.Connection, schema_version: int) -> None:
    """Check Studio cross-record references before an import transaction begins.

    Every current Android schema-3 export has both Studio streams, including empty.
    Import storage merely retains versions; it must not authorize model actions.
    """
    need(schema_version in (2, 3))
    count = db.execute("SELECT COUNT(*) FROM records WHERE kind IN ('studio-project','studio-version')").fetchone()[0]
    if schema_version == 2:
        need(count == 0)
        return
    projects = {}
    for key, raw in db.execute("SELECT key,payload FROM records WHERE kind='studio-project'"):
        project = parse(raw.decode('utf-8'), 32 * 1024 * 1024)
        identifier = text(project['objectId'], 100, 1)
        need(ID.fullmatch(identifier) is not None and key == identifier)
        revision = integer(project['revision'], 1, MAX_REVISION)
        document(project['committed'])
        session(project['session'])
        row = db.execute("SELECT payload FROM records WHERE kind='workspace' AND key=?", (identifier,)).fetchone()
        need(row is not None)
        header = json.loads(row[0])  # Stager already enforced strict JSON and duplicate-key refusal.
        need(header['type'] == 'ARTIFACT' and header['contentRef'] == 'studio:' + identifier
             and isinstance(header['title'], str) and 0 < len(header['title'].strip()) <= 120)
        projects[identifier] = revision
    for key, raw in db.execute("SELECT key,payload FROM records WHERE kind='studio-version'"):
        version = parse(raw.decode('utf-8'), 32 * 1024 * 1024)
        identifier = text(version['objectId'], 100, 1)
        revision = integer(version['revision'], 1, MAX_REVISION)
        need(ID.fullmatch(identifier) is not None and key == f'{identifier}:{revision}')
        need(identifier in projects and revision <= projects[identifier])
        integer(version['savedAt'], 0, 2**63-1)
        document(version['document'])
    for (raw,) in db.execute("SELECT payload FROM records WHERE kind='workspace'"):
        header = json.loads(raw)
        content_ref = header['contentRef']
        if content_ref.startswith('studio:'):
            need(header['id'] in projects)
