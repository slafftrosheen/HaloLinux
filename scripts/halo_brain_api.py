#!/usr/bin/env python3
"""Standalone read-only Brain API. Linux Unix socket only; never a TCP listener.

Run as the data owner. Initialize/import through halo_brain_import.py separately.
No access logs, archive passwords, provider calls, write endpoints or auto-recovery.
"""
from __future__ import annotations

from contextlib import ExitStack
import os
from pathlib import Path
import re
import signal
import socket
import sqlite3
import stat
import struct
import sys
import threading
import time

import halo_brain_archive as archive
import halo_brain_import as store
from halo_brain_reader import ApiError, BrainReader, MAX_RESPONSE, ROUTES, failure, require
from halo_brain_records import canonical

SOCKET_NAME = 'brain-api.sock'
MAX_HEADERS = 8192
MAX_REQUEST = 16_384
REQUEST_SECONDS = 3.0
REASONS = {200: 'OK', 400: 'Bad Request', 403: 'Forbidden', 404: 'Not Found',
           405: 'Method Not Allowed', 408: 'Request Timeout', 409: 'Conflict',
           413: 'Content Too Large', 415: 'Unsupported Media Type',
           422: 'Unprocessable Content', 500: 'Internal Server Error', 503: 'Service Unavailable'}


def peer_uid(connection: socket.socket) -> int:
    """Kernel peer identity; do not accept identity claimed in a JSON/header field."""
    return struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                                                    struct.calcsize('3i')))[1]


def read_request(connection: socket.socket, timeout: float) -> tuple[str, dict]:
    """A narrow HTTP/1.1 query transport, not a general web/proxy server.

    Exactly one POST with an explicit Content-Length and JSON body per connection.
    Deadline covers all reads, not just individual recv calls (slow-client bound).
    """
    deadline = time.monotonic() + timeout
    buffer = bytearray()

    def receive(maximum: int) -> bytes:
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError()
        connection.settimeout(remaining)
        block = connection.recv(maximum)
        require(bool(block), 'incomplete_request')
        return block

    while b'\r\n\r\n' not in buffer:
        require(len(buffer) <= MAX_HEADERS, 'headers_too_large', 413)
        buffer.extend(receive(4096))
    end = buffer.index(b'\r\n\r\n')
    require(end + 4 <= MAX_HEADERS, 'headers_too_large', 413)
    try: lines = bytes(buffer[:end]).decode('ascii').split('\r\n')
    except UnicodeError: raise ApiError('invalid_headers') from None
    require(1 <= len(lines) <= 65, 'invalid_headers')
    parts = lines[0].split(' ')
    require(len(parts) == 3 and parts[2] == 'HTTP/1.1', 'invalid_request_line')
    method, path, _ = parts
    require(method == 'POST', 'method_not_allowed', 405)
    require(path in ROUTES, 'not_found', 404)
    headers = {}
    for line in lines[1:]:
        require(':' in line and not line.startswith((' ', '\t')), 'invalid_headers')
        name, value = line.split(':', 1)
        require(re.fullmatch("[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) is not None
                and all(32 <= ord(c) < 127 or c == '\t' for c in value), 'invalid_headers')
        name = name.lower()
        require(name not in headers, 'duplicate_header')
        headers[name] = value.strip(' \t')
    require(not {'origin', 'referer'} & headers.keys(), 'browser_request_refused', 403)
    require(not {'transfer-encoding', 'expect', 'upgrade'} & headers.keys(), 'unsupported_framing')
    require(headers.get('host') == 'localhost', 'invalid_host')
    require(headers.get('content-type', '').lower() in ('application/json', 'application/json; charset=utf-8'),
            'json_required', 415)
    length = headers.get('content-length', '')
    require(re.fullmatch('[0-9]{1,10}', length) is not None, 'invalid_content_length')
    size = int(length)
    require(0 < size <= MAX_REQUEST, 'request_too_large', 413)
    body = bytearray(buffer[end+4:])
    require(len(body) <= size, 'trailing_request_bytes')
    while len(body) < size: body.extend(receive(min(4096, size-len(body))))
    try:
        data = archive._json_object(bytes(body), archive.Limits(max_json_depth=16))
    except (archive.ArchiveError, ValueError, UnicodeError, RecursionError):
        raise ApiError('invalid_json') from None
    return path, data


def send_response(connection: socket.socket, status: int, body: dict, timeout: float) -> None:
    payload = canonical(body)
    require(len(payload) <= MAX_RESPONSE, 'response_too_large', 422)
    header = (f'HTTP/1.1 {status} {REASONS[status]}\r\n'
              'Content-Type: application/json; charset=utf-8\r\n'
              f'Content-Length: {len(payload)}\r\n'
              'Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n'
              'Connection: close\r\n\r\n').encode('ascii')
    connection.settimeout(timeout)
    connection.sendall(header + payload)


class ApiServer:
    def __init__(self, reader: BrainReader, runtime_dir: Path | str, *, request_timeout=REQUEST_SECONDS):
        require(type(request_timeout) in (int, float) and 0 < request_timeout <= 10)
        self.reader, self.runtime_dir = reader, Path(runtime_dir).absolute()
        self.path = self.runtime_dir / SOCKET_NAME
        require(len(os.fsencode(self.path)) < 108, 'socket_path_too_long')
        self.request_timeout = request_timeout
        self.listener = None
        self.stack = None
        self.directory = None
        self.socket_identity = None

    def __enter__(self):
        self.stack = ExitStack()
        try:
            # Reuse descriptor-based no-symlink traversal, UID/mode checks and
            # directory locking. A dedicated runtime directory must already exist.
            self.directory = self.stack.enter_context(store._directory(self.runtime_dir))
            require(not os.listdir(self.directory), 'runtime_directory_not_empty')
            self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.listener.bind(f'/proc/self/fd/{self.directory}/{SOCKET_NAME}')
            info = os.stat(SOCKET_NAME, dir_fd=self.directory, follow_symlinks=False)
            self.socket_identity = (info.st_dev, info.st_ino)
            os.chmod(SOCKET_NAME, 0o600, dir_fd=self.directory, follow_symlinks=False)
            self.listener.listen(8)
            self.listener.settimeout(.1)
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *unused):
        if self.listener is not None:
            self.listener.close()
            self.listener = None
        try:
            if self.directory is not None and self.socket_identity is not None:
                try: info = os.stat(SOCKET_NAME, dir_fd=self.directory, follow_symlinks=False)
                except FileNotFoundError: info = None
                if info is not None and stat.S_ISSOCK(info.st_mode) and (info.st_dev, info.st_ino) == self.socket_identity:
                    os.unlink(SOCKET_NAME, dir_fd=self.directory)
        finally:
            if self.stack is not None: self.stack.close()
            self.stack = self.directory = self.socket_identity = None

    def _handle(self, connection):
        try:
            require(peer_uid(connection) == os.getuid(), 'peer_not_allowed', 403)
            path, data = read_request(connection, self.request_timeout)
            status, body = self.reader.dispatch(path, data)
        except ApiError as error:
            status, body = failure(error.code, error.status)
        except TimeoutError:
            status, body = failure('request_timeout', 408)
        except (OSError, ValueError):
            status, body = failure('invalid_request', 400)
        except Exception:
            status, body = failure('internal_error', 500)
        try: send_response(connection, status, body, self.request_timeout)
        except (OSError, ApiError): pass  # Disconnected client; never log payload/selector.

    def serve(self, stop: threading.Event) -> None:
        require(self.listener is not None, 'server_not_started')
        # Bounded single worker. No unbounded thread pool or concurrent store scans.
        while not stop.is_set():
            try: connection, _ = self.listener.accept()
            except TimeoutError: continue
            with connection: self._handle(connection)


def main(argv=None) -> int:
    parser = store.SafeParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--runtime-dir', type=Path, required=True)
    parser.add_argument('--source', action='append', required=True, help='Allowed local import source label; repeat for multiple sources')
    parser.add_argument('--allow-inspection', action='store_true', help='Enable explicit raw record inspection; disabled by default')
    stop = threading.Event()
    handlers = {}
    try:
        args = parser.parse_args(argv)
        reader = BrainReader(args.store, sources=args.source, allow_inspection=args.allow_inspection)
        for signum in (signal.SIGINT, signal.SIGTERM):
            handlers[signum] = signal.signal(signum, lambda *_: stop.set())
        with ApiServer(reader, args.runtime_dir) as server:
            print('Halo Brain API ready: private Unix socket, read-only, no TCP.', flush=True)
            server.serve(stop)
        return 0
    except (ApiError, archive.ArchiveError, OSError, ValueError, sqlite3.Error):
        print('halo-brain-api: Start failed; check the private store and empty private runtime directory.', file=sys.stderr)
        return 2
    finally:
        for signum, handler in handlers.items(): signal.signal(signum, handler)



if __name__ == '__main__':
    raise SystemExit(main())
