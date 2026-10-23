#!/usr/bin/env python3
"""Small stdlib Unix HTTP client for the standalone Brain API (no TCP fallback).

CLI requests come from stdin, keeping personal query text out of process arguments.
The server must run as the same Linux UID. No automatic retries or remote forwarding.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import http.client
import json
import math
import os
from pathlib import Path
import socket
import struct
import sys

MAX_REQUEST = 16_384
MAX_RESPONSE = 8 * 1024**2
ENDPOINTS = frozenset(('/v1/status', '/v1/records/list', '/v1/records/read', '/v1/memory/search'))


class ClientError(Exception):
    """Fixed safe message; server errors are available as a bounded code/status."""
    def __init__(self, code='request_failed', status=None):
        super().__init__('Brain API request failed.')
        self.code, self.status = code, status


def _pairs(items):
    result = {}
    for key, value in items:
        if key in result: raise ClientError('invalid_json')
        result[key] = value
    return result


def _constant(_):
    raise ClientError('invalid_json')


def _float(raw):
    value = float(raw)
    if not math.isfinite(value): raise ClientError('invalid_json')
    return value


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path, timeout):
        super().__init__('localhost', timeout=timeout)
        self.path = os.fspath(path)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        try:
            self.sock.connect(self.path)
            uid = struct.unpack('3i', self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                                                         struct.calcsize('3i')))[1]
            if uid != os.getuid(): raise ClientError('peer_not_allowed')
        except BaseException:
            self.close()
            raise


class BrainClient:
    def __init__(self, socket_path: Path | str, *, timeout: float = 8.0):
        if not isinstance(socket_path, (str, Path)) or not Path(socket_path).is_absolute() or type(timeout) not in (int, float) or not 0 < timeout <= 30:
            raise ClientError('invalid_client_configuration')
        self.socket_path, self.timeout = socket_path, timeout

    def request(self, endpoint: str, payload: dict) -> dict:
        if not isinstance(endpoint, str) or endpoint not in ENDPOINTS or not isinstance(payload, dict): raise ClientError('invalid_request')
        try:
            raw = json.dumps(payload, ensure_ascii=True, allow_nan=False, separators=(',', ':')).encode('ascii')
            if len(raw) > MAX_REQUEST: raise ClientError('request_too_large')
            with closing(UnixConnection(self.socket_path, self.timeout)) as connection:
                connection.request('POST', endpoint, body=raw, headers={'Content-Type': 'application/json', 'Connection': 'close'})
                response = connection.getresponse()
                length = response.getheader('Content-Length', '')
                if not length.isascii() or not length.isdecimal() or not 0 < int(length) <= MAX_RESPONSE:
                    raise ClientError('invalid_response')
                if response.getheader('Content-Type') != 'application/json; charset=utf-8': raise ClientError('invalid_response')
                body = response.read(MAX_RESPONSE + 1)
                if len(body) != int(length): raise ClientError('invalid_response')
                parsed = json.loads(body, object_pairs_hook=_pairs, parse_constant=_constant, parse_float=_float)
                if not isinstance(parsed, dict) or type(parsed.get('api_version')) is not int or parsed['api_version'] != 1:
                    raise ClientError('unsupported_response')
                if response.status != 200 or parsed.get('ok') is not True:
                    # Expose a known finite set only; a compromised server cannot make
                    # our CLI echo arbitrary text via an error-code field.
                    codes = {'inspection_disabled', 'source_not_allowed', 'record_not_found',
                             'snapshot_changed', 'cursor_mismatch', 'store_replaced',
                             'store_unavailable', 'store_invalid', 'query_budget_exceeded',
                             'invalid_request', 'invalid_source', 'invalid_cursor', 'response_too_large'}
                    error = parsed.get('error')
                    code = error.get('code') if isinstance(error, dict) else None
                    raise ClientError(code if isinstance(code, str) and code in codes else 'request_failed', response.status)
                if not isinstance(parsed.get('result'), dict): raise ClientError('invalid_response')
                return parsed
        except ClientError:
            raise
        except (OSError, ValueError, TypeError, http.client.HTTPException, RecursionError):
            raise ClientError() from None


class Parser(argparse.ArgumentParser):
    def error(self, message): raise ClientError('invalid_arguments')


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--socket', type=Path, required=True)
    parser.add_argument('--endpoint', required=True)
    try:
        args = parser.parse_args(argv)
        raw = sys.stdin.buffer.read(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST: raise ClientError('request_too_large')
        payload = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_constant, parse_float=_float)
        result = BrainClient(args.socket).request(args.endpoint, payload)
        print(json.dumps(result, ensure_ascii=True, allow_nan=False))
        return 0
    except ClientError as error:
        print(f'halo-brain-client: {error.code}', file=sys.stderr)
        return 2
    except (ValueError, TypeError, OSError, RecursionError):
        print('halo-brain-client: invalid_request', file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print('halo-brain-client: cancelled', file=sys.stderr)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
