#!/usr/bin/env python3
"""Small, local-admin invitation service. Deploy behind an HTTPS reverse proxy."""

import argparse
import base64
import binascii
from collections import OrderedDict
import hashlib
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import stat
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import uuid


MAX_BODY = 1024
MAX_SUBSCRIPTION = 512 * 1024
MAX_NODES = 500
MAX_NODE_LENGTH = 16384
CODE_PATTERN = re.compile(r"[A-Z0-9-]{8,128}", re.ASCII)
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}", re.ASCII)
NODE_SCHEMES = {
    "ss", "vless", "vmess", "trojan", "socks", "socks4", "socks5",
    "wireguard", "hysteria2", "hy2",
}


class ServiceError(Exception):
    def __init__(self, error, status=400):
        super().__init__(error)
        self.error = error
        self.status = status


def normalize_code(value):
    if not isinstance(value, str) or not value.isascii():
        raise ServiceError("invalid_request")
    value = value.strip().upper()
    if not CODE_PATTERN.fullmatch(value):
        raise ServiceError("invalid_request")
    return value


def normalize_request_id(value):
    if not isinstance(value, str):
        raise ServiceError("invalid_request")
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        raise ServiceError("invalid_request") from None
    if str(parsed) != value.lower():
        raise ServiceError("invalid_request")
    return str(parsed)


def digest(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def validate_base_url(value, allow_insecure_dev=False):
    try:
        url = urlsplit(value)
        port = url.port
        host = url.hostname
    except ValueError:
        raise ValueError("public base URL must be an HTTPS origin") from None
    if (
        not host
        or len(value) > 2048
        or not value.isascii()
        or any(ord(c) <= 32 or ord(c) == 127 for c in value)
        or "\\" in value
        or url.username is not None
        or url.password is not None
        or url.path not in ("", "/")
        or "?" in value
        or "#" in value
        or port == 0
    ):
        raise ValueError("public base URL must be an HTTPS origin without credentials")
    try:
        local = ipaddress.ip_address(host).is_loopback
    except ValueError:
        local = host == "localhost"
    insecure_ok = allow_insecure_dev and (local or host == "10.0.2.2")
    if url.scheme != "https" and not (url.scheme == "http" and insecure_ok):
        raise ValueError("HTTPS required (local HTTP needs --allow-insecure-dev)")
    # Reject malformed DNS authorities rather than reflecting them into token URLs.
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if len(host) > 253 or not all(
            re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
            for label in host.rstrip(".").split(".")
        ):
            raise ValueError("invalid public host") from None
    authority = "[" + host + "]" if ":" in host else host
    if port is not None:
        authority += ":" + str(port)
    if url.netloc.lower() != authority.lower() or "%" in host:
        raise ValueError("invalid public authority")
    return value.rstrip("/")


def validate_nodes(content):
    if not isinstance(content, bytes) or not 0 < len(content) <= MAX_SUBSCRIPTION:
        raise ValueError("subscription must be 1..524288 bytes")
    try:
        text = content.decode("utf-8").strip()
        if "://" not in text:
            encoded = "".join(text.split())
            text = base64.b64decode(encoded, validate=True).decode("utf-8").strip()
    except (UnicodeError, binascii.Error, ValueError):
        raise ValueError("expected node URI lines or a standard base64 subscription") from None
    lines = []
    seen = set()
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        # Match Android String.length, including surrogate pairs in node labels.
        if len(line.encode("utf-16-le")) // 2 > MAX_NODE_LENGTH:
            raise ValueError("node URI lines must not exceed 16384 characters")
        scheme, separator, payload = line.partition("://")
        if (
            scheme not in NODE_SCHEMES
            or not separator
            or not payload
            or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in line)
        ):
            raise ValueError("unsupported node URI (no HTTP URLs or core JSON)")
        if line not in seen:
            seen.add(line)
            lines.append(line)
            if len(lines) > MAX_NODES:
                raise ValueError("subscription must not exceed 500 unique nodes")
    if not lines:
        raise ValueError("subscription must contain nodes")
    return ("\n".join(lines) + "\n").encode("utf-8")


SCHEMA = """
CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS groups (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, nodes BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS codes (
    hash TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    expires_at REAL NOT NULL,
    max_uses INTEGER NOT NULL,
    uses INTEGER NOT NULL DEFAULT 0,
    grant_seconds INTEGER NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS grants (
    token_hash TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL REFERENCES codes(hash),
    request_id TEXT NOT NULL,
    issued_name TEXT NOT NULL,
    expires_at REAL NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0,
    UNIQUE(code_hash, request_id)
);
"""


class Store:
    def __init__(self, path, clock=time.time):
        self.path = Path(path).absolute()
        self.clock = clock
        self._prepare_path()
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                "INSERT OR IGNORE INTO metadata VALUES ('token_key', ?)",
                (secrets.token_bytes(32),),
            )
            self.key = bytes(db.execute(
                "SELECT value FROM metadata WHERE key='token_key'"
            ).fetchone()[0])
        if len(self.key) != 32:
            raise ValueError("invalid database token key")
        self._check_files()

    def _prepare_path(self):
        # SQLite's WAL/SHM inherit database permissions; a private directory also
        # prevents another local account replacing a file between checks.
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        parent = self.path.parent.stat()
        if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) & 0o077:
            raise ValueError("database directory must be owned by this user with mode 0700")
        self._check_files()
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            self._check_files()
        else:
            os.close(fd)

    def _check_files(self):
        for suffix in ("", "-wal", "-shm", "-journal"):
            path = Path(str(self.path) + suffix)
            if path.is_symlink():
                raise ValueError("database files must not be symlinks")
            if path.exists():
                info = path.stat()
                if (
                    not stat.S_ISREG(info.st_mode)
                    or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) & 0o077
                ):
                    raise ValueError("database files must be owned by this user with mode 0600")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return ClosingConnection(db)

    def put_group(self, group_id, name, content):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", group_id):
            raise ValueError("group id must be 1..64 ASCII letters, digits, underscores or hyphens")
        if not isinstance(name, str) or not name.strip() or len(name) > 100 or any(
            ord(c) < 32 or ord(c) == 127 for c in name
        ):
            raise ValueError("group name must be 1..100 characters without controls")
        nodes = validate_nodes(content)
        with self.connect() as db:
            db.execute(
                "INSERT INTO groups VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE "
                "SET name=excluded.name, nodes=excluded.nodes",
                (group_id, name, nodes),
            )

    def issue(self, group_id, max_uses=1, expires_days=30, grant_days=30):
        if not isinstance(max_uses, int) or not 1 <= max_uses <= 1_000_000:
            raise ValueError("max uses must be 1..1000000")
        if any(not math.isfinite(v) or not 0 < v <= 3650 for v in (expires_days, grant_days)):
            raise ValueError("expiry and grant days must be >0 and <=3650")
        # 160 random bits; human-friendly uppercase, no implicit weak custom codes.
        code = base64.b32encode(secrets.token_bytes(20)).decode("ascii")
        code = "-".join(code[i:i + 8] for i in range(0, len(code), 8))
        with self.connect() as db:
            if not db.execute("SELECT 1 FROM groups WHERE id=?", (group_id,)).fetchone():
                raise ValueError("unknown group")
            db.execute(
                "INSERT INTO codes(hash,group_id,expires_at,max_uses,grant_seconds) "
                "VALUES (?,?,?,?,?)",
                (digest(code), group_id, self.clock() + expires_days * 86400,
                 max_uses, max(1, int(grant_days * 86400))),
            )
        return code

    def token_for(self, code_hash, request_id):
        raw = hmac.new(
            self.key, ("subscription:v1:" + code_hash + ":" + request_id).encode("ascii"),
            hashlib.sha256,
        ).digest()
        return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

    def redeem(self, code, request_id):
        code_hash = digest(normalize_code(code))
        request_id = normalize_request_id(request_id)
        token = self.token_for(code_hash, request_id)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            now = self.clock()
            code_row = db.execute("SELECT * FROM codes WHERE hash=?", (code_hash,)).fetchone()
            if not code_row or code_row["revoked"] or code_row["expires_at"] <= now:
                raise ServiceError("invalid_code", 404)
            grant = db.execute(
                "SELECT * FROM grants WHERE code_hash=? AND request_id=?",
                (code_hash, request_id),
            ).fetchone()
            # Retries survive exhaustion, never either expiry or revocation.
            # Name is a snapshot of the first result.
            if grant:
                if grant["revoked"] or grant["expires_at"] <= now:
                    raise ServiceError("invalid_code", 404)
                return grant["issued_name"], token
            if code_row["uses"] >= code_row["max_uses"]:
                raise ServiceError("invalid_code", 404)
            name = db.execute(
                "SELECT name FROM groups WHERE id=?", (code_row["group_id"],)
            ).fetchone()[0]
            db.execute(
                "INSERT INTO grants(token_hash,code_hash,request_id,issued_name,expires_at) "
                "VALUES (?,?,?,?,?)",
                (digest(token), code_hash, request_id, name,
                 min(code_row["expires_at"], now + code_row["grant_seconds"])),
            )
            db.execute("UPDATE codes SET uses=uses+1 WHERE hash=?", (code_hash,))
        return name, token

    def _authorized_grant(self, token):
        if not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token):
            raise ServiceError("invalid_code", 404)
        with self.connect() as db:
            now = self.clock()
            row = db.execute(
                "SELECT g.nodes,t.issued_name,min(t.expires_at,c.expires_at) AS expires_at "
                "FROM grants t JOIN codes c ON c.hash=t.code_hash "
                "JOIN groups g ON g.id=c.group_id "
                "WHERE t.token_hash=? AND t.revoked=0 AND c.revoked=0 "
                "AND t.expires_at>? AND c.expires_at>?",
                (digest(token), now, now),
            ).fetchone()
        if not row:
            raise ServiceError("invalid_code", 404)
        return row, now

    def subscription(self, token):
        row, _ = self._authorized_grant(token)
        return bytes(row["nodes"])

    def validate_entitlement(self, token):
        row, now = self._authorized_grant(token)
        return {"server_time": now, "expires_at": row["expires_at"], "name": row["issued_name"]}

    def revoke_code(self, code_hash):
        with self.connect() as db:
            return db.execute("UPDATE codes SET revoked=1 WHERE hash=?", (code_hash,)).rowcount

    def revoke_token(self, token_hash):
        with self.connect() as db:
            return db.execute("UPDATE grants SET revoked=1 WHERE token_hash=?", (token_hash,)).rowcount

    def inventory(self, kind):
        queries = {
            "groups": "SELECT id,name FROM groups ORDER BY id",
            "codes": "SELECT hash,group_id,expires_at,max_uses,uses,grant_seconds,revoked FROM codes",
            "grants": "SELECT token_hash,code_hash,expires_at,revoked FROM grants",
        }
        with self.connect() as db:
            return [dict(row) for row in db.execute(queries[kind])]

    def backup(self, destination):
        path = Path(destination).absolute()
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = path.parent.stat()
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
            raise ValueError("backup directory must be private (0700)")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(fd)
        with self.connect() as source:
            target = sqlite3.connect(path)
            try:
                source.backup(target)
            finally:
                target.close()


class ClosingConnection:
    """sqlite3's own context manager commits but does not close connections."""
    def __init__(self, db):
        self.db = db

    def __enter__(self):
        self.db.__enter__()
        return self.db

    def __exit__(self, *args):
        try:
            return self.db.__exit__(*args)
        finally:
            self.db.close()


class RateLimiter:
    def __init__(self, limit=30, window=60, capacity=4096, clock=time.monotonic):
        self.limit = limit
        self.window = window
        self.capacity = capacity
        self.clock = clock
        self.entries = OrderedDict()
        self.lock = threading.Lock()

    def allow(self, address):
        with self.lock:
            now = self.clock()
            # Ordered by first request, so expiration is bounded by cache size.
            while self.entries and next(iter(self.entries.values()))[0] <= now - self.window:
                self.entries.popitem(last=False)
            entry = self.entries.get(address)
            if entry is None:
                # Fail closed on overflow instead of letting address churn evict
                # active limits. The reverse proxy should impose a global limit.
                if len(self.entries) >= self.capacity:
                    return False
                self.entries[address] = [now, 1]
                return True
            if entry[1] >= self.limit:
                return False
            entry[1] += 1
            return True


class InvitationServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store, public_base_url, allow_insecure_dev=False,
                 rate_limit=30, max_connections=32, client_timeout=5):
        self.public_base_url = validate_base_url(public_base_url, allow_insecure_dev)
        self.store = store
        self.limiter = RateLimiter(limit=rate_limit)
        self.slots = threading.BoundedSemaphore(max_connections)
        self.client_timeout = client_timeout
        super().__init__(address, Handler)

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(self.client_timeout)
        return sock, address

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # Never print exception values/tracebacks containing user-controlled data.
        sys.stderr.write("invitation-service: request failed\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    server_version = "InvitationService"
    sys_version = ""

    def log_message(self, format, *args):
        # Default http.server access/error logs include bearer-token paths.
        pass

    def send_error(self, code, message=None, explain=None):
        self.json_response(400, {"error": "invalid_request"})

    def response(self, status, content, content_type):
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        if status == 429:
            self.send_header("Retry-After", "60")
        self.end_headers()
        self.wfile.write(content)

    def json_response(self, status, value):
        self.response(status, json.dumps(value, ensure_ascii=True).encode("utf-8"),
                      "application/json; charset=utf-8")

    def handle_api(self):
        try:
            # Forwarded and X-Forwarded-For are deliberately ignored: only the
            # TCP peer is trusted. Behind a proxy this is a shared coarse limit.
            if not self.server.limiter.allow(self.client_address[0]):
                raise ServiceError("rate_limited", 429)
            if len(self.path) > 256 or self.headers.get("Expect") is not None:
                raise ServiceError("invalid_request")
            if self.command == "GET" and self.path == "/healthz":
                self.json_response(200, {"status": "ok"})
            elif self.command == "POST" and self.path == "/v1/invitations/redeem":
                self.redeem()
            elif self.command == "POST" and self.path == "/v1/entitlements/validate":
                self.validate_entitlement()
            elif self.command == "GET" and self.path.startswith("/v1/subscriptions/"):
                token = self.path[len("/v1/subscriptions/"):]
                self.response(200, self.server.store.subscription(token), "text/plain; charset=utf-8")
            else:
                raise ServiceError("invalid_request", 404)
        except ServiceError as exc:
            self.json_response(exc.status, {"error": exc.error})
        except (socket.timeout, TimeoutError):
            self.json_response(400, {"error": "invalid_request"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            sys.stderr.write("invitation-service: request failed\n")
            self.json_response(500, {"error": "server_error"})

    def read_json_body(self):
        lengths = self.headers.get_all("Content-Length", [])
        types = self.headers.get_all("Content-Type", [])
        if (
            self.headers.get("Transfer-Encoding") is not None
            or len(lengths) != 1
            or not re.fullmatch(r"[0-9]{1,4}", lengths[0])
            or not 1 <= int(lengths[0]) <= MAX_BODY
            or len(types) != 1
            or types[0].lower().strip() not in ("application/json", "application/json; charset=utf-8")
            or self.headers.get("Content-Encoding") is not None
        ):
            raise ServiceError("invalid_request")
        raw = self.rfile.read(int(lengths[0]))
        if len(raw) != int(lengths[0]):
            raise ServiceError("invalid_request")
        try:
            body = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
        except (ValueError, UnicodeError, RecursionError):
            raise ServiceError("invalid_request") from None
        if not isinstance(body, dict):
            raise ServiceError("invalid_request")
        return body

    def validate_entitlement(self):
        body = self.read_json_body()
        if set(body) - {"token"}:
            raise ServiceError("invalid_request")
        self.json_response(200, self.server.store.validate_entitlement(body.get("token")))

    def redeem(self):
        body = self.read_json_body()
        if set(body) != {"code", "request_id"}:
            raise ServiceError("invalid_request")
        name, token = self.server.store.redeem(body["code"], body["request_id"])
        self.json_response(200, {
            "name": name,
            "subscription_url": self.server.public_base_url + "/v1/subscriptions/" + token,
        })

    do_POST = handle_api
    do_GET = handle_api


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def positive_int(value):
    result = int(value)
    if result <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return result


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=str(Path(__file__).parent / "data" / "service.sqlite"),
                        help="SQLite path in a private, owned 0700 directory")
    commands = parser.add_subparsers(dest="command", required=True)
    group = commands.add_parser("group", help="create/update group from a local node file")
    group.add_argument("id")
    group.add_argument("--name", required=True)
    group.add_argument("--file", required=True)
    invite = commands.add_parser("invite", help="issue a code; plaintext printed ONCE")
    invite.add_argument("group")
    invite.add_argument("--max-uses", type=positive_int, default=1)
    invite.add_argument("--expires-days", type=float, default=30)
    invite.add_argument("--grant-days", type=float, default=30)
    listing = commands.add_parser("list", help="local inventory (hashes, never codes/tokens)")
    listing.add_argument("kind", choices=("groups", "codes", "grants"))
    revoke_code = commands.add_parser("revoke-code", help="revoke invitation and all its grants")
    revoke_code.add_argument("--hash", required=True, help="hash from list codes")
    revoke_token = commands.add_parser("revoke-token", help="revoke one grant")
    revoke_token.add_argument("--hash", required=True, help="token_hash from list grants")
    backup = commands.add_parser("backup", help="consistent online SQLite backup; new file only")
    backup.add_argument("destination")
    serve = commands.add_parser("serve", help="serve behind HTTPS proxy; loopback by default")
    serve.add_argument("--public-base-url", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=18084)
    serve.add_argument("--allow-insecure-dev", action="store_true")
    serve.add_argument("--rate-limit", type=positive_int, default=30,
                       help="requests per TCP peer per minute (all routes); default 30")
    serve.add_argument("--max-connections", type=positive_int, default=32)
    args = parser.parse_args(argv)
    try:
        if args.command == "serve":
            validate_base_url(args.public_base_url, args.allow_insecure_dev)
        store = Store(args.db)
        if args.command == "group":
            with open(args.file, "rb") as source:
                content = source.read(MAX_SUBSCRIPTION + 1)
            store.put_group(args.id, args.name, content)
            print("Group saved.")
        elif args.command == "invite":
            print(store.issue(args.group, args.max_uses, args.expires_days, args.grant_days))
        elif args.command == "list":
            print(json.dumps(store.inventory(args.kind), indent=2))
        elif args.command in ("revoke-code", "revoke-token"):
            if not re.fullmatch(r"[0-9a-f]{64}", args.hash):
                raise ValueError("expected SHA-256 hex hash from inventory")
            method = store.revoke_code if args.command == "revoke-code" else store.revoke_token
            if not method(args.hash):
                raise ValueError("unknown hash")
            print("Revoked.")
        elif args.command == "backup":
            store.backup(args.destination)
            print("Backup created.")
        elif args.command == "serve":
            server = InvitationServer(
                (args.host, args.port), store, args.public_base_url, args.allow_insecure_dev,
                rate_limit=args.rate_limit, max_connections=args.max_connections,
            )
            print("Invitation service started; request logging disabled.", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                server.server_close()
    except (ValueError, OSError, sqlite3.Error) as exc:
        # Admin-only errors deliberately omit database contents and supplied secrets.
        print("Error: " + (str(exc) if isinstance(exc, ValueError) else "storage or network operation failed"),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
