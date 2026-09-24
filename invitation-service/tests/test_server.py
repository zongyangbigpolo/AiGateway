import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr
from datetime import datetime
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import unittest
from urllib.parse import urlsplit
import uuid

SERVICE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_DIR))
import server  # noqa: E402


NODES = b"vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls#Demo\n"
UPDATED = b"trojan://example-password@new.example.invalid:443?security=tls#New\n"


class StoreCase(unittest.TestCase):
    def setUp(self):
        # All ephemeral test data stays within the owned project subtree.
        self.directory = SERVICE_DIR / (".test-data-" + uuid.uuid4().hex)
        self.directory.mkdir(mode=0o700)
        self.now = 1_000_000.0
        self.store = server.Store(self.directory / "service.sqlite", clock=lambda: self.now)
        self.store.put_group("demo", "Demo group", NODES)

    def tearDown(self):
        shutil.rmtree(self.directory)

    def redeem(self, code, request_id=None):
        return self.store.redeem(code, request_id or str(uuid.uuid4()))

    def assert_invalid(self, fn, *args):
        with self.assertRaises(server.ServiceError) as caught:
            fn(*args)
        self.assertEqual(caught.exception.error, "invalid_code")
        self.assertEqual(caught.exception.status, 404)


class StoreTests(StoreCase):
    def test_normalization_and_no_plaintext_code_or_token_in_db(self):
        code = self.store.issue("demo")
        name, token = self.redeem(" \n" + code.lower() + "\t ")
        self.assertEqual(name, "Demo group")
        self.assertEqual(self.store.subscription(token), NODES)
        with self.store.connect() as db:
            dumped = "\n".join(db.iterdump())
        self.assertNotIn(code, dumped)
        self.assertNotIn(token, dumped)
        self.assertIn(server.digest(code), dumped)
        self.assertIn(server.digest(token), dumped)

    def test_invalid_expired_exhausted_and_revoked_codes(self):
        self.assert_invalid(self.redeem, "UNKNOWN-CODE")
        code = self.store.issue("demo")
        _, token = self.redeem(code)
        self.assert_invalid(self.redeem, code)
        self.store.revoke_code(server.digest(code))
        self.assert_invalid(self.store.subscription, token)
        self.assert_invalid(self.redeem, code)
        expired = self.store.issue("demo")
        self.now += 30 * 86400
        self.assert_invalid(self.redeem, expired)

    def test_idempotent_retry_survives_restart_but_not_invite_expiry(self):
        code = self.store.issue("demo", expires_days=1, grant_days=30)
        request_id = str(uuid.uuid4())
        first = self.redeem(code, request_id)
        self.store.put_group("demo", "Renamed group", UPDATED)
        self.now += 86400 - 0.5
        restarted = server.Store(self.store.path, clock=lambda: self.now)
        self.assertEqual(restarted.redeem(code.lower(), request_id.upper()), first)
        self.assertEqual(restarted.subscription(first[1]), UPDATED)
        self.assertEqual(restarted.validate_entitlement(first[1]), {
            "server_time": self.now, "expires_at": 1_000_000 + 86400, "name": first[0],
        })
        self.assertEqual(self.store.inventory("codes")[0]["uses"], 1)
        self.now += 0.5
        self.assert_invalid(self.redeem, code, request_id)
        self.assert_invalid(self.store.subscription, first[1])
        self.assert_invalid(self.store.validate_entitlement, first[1])

    def test_existing_database_grants_obey_minimum_expiry_without_migration(self):
        for code_days, grant_days in ((1, 30), (30, 1)):
            with self.subTest(code_days=code_days, grant_days=grant_days):
                code = self.store.issue("demo", expires_days=code_days, grant_days=grant_days)
                request_id = str(uuid.uuid4())
                first = self.redeem(code, request_id)
                # Reproduce pre-upgrade grants: expiry was independent of the code.
                with self.store.connect() as db:
                    db.execute("UPDATE grants SET expires_at=? WHERE token_hash=?",
                               (self.now + grant_days * 86400, server.digest(first[1])))
                    before = "\n".join(db.iterdump())
                restarted = server.Store(self.store.path, clock=lambda: self.now)
                self.assertEqual(restarted.validate_entitlement(first[1])["expires_at"],
                                 self.now + 86400)
                with restarted.connect() as db:
                    self.assertEqual("\n".join(db.iterdump()), before)
                self.now += 86400
                self.assert_invalid(restarted.redeem, code, request_id)
                self.assert_invalid(restarted.subscription, first[1])
                self.assert_invalid(restarted.validate_entitlement, first[1])

    def test_six_one_use_codes_keep_fixed_november_deadline(self):
        issued_at = datetime.fromisoformat("2026-09-24T13:44:55+08:00").timestamp()
        deadline = datetime.fromisoformat("2026-11-24T13:44:55+08:00").timestamp()
        self.now = issued_at
        self.store.put_group("private-subscription", "Private subscription", NODES)
        codes = [self.store.issue("private-subscription", expires_days=61, grant_days=61)
                 for _ in range(6)]
        self.assertEqual({row["expires_at"] for row in self.store.inventory("codes")},
                         {deadline})
        # Synthetic fixtures only: redeem at progressively later times, including
        # immediately before expiry, without granting another 61 days.
        for index, code in enumerate(codes):
            self.now = (issued_at + index * 10 * 86400) if index < 5 else deadline - 0.5
            request_id = str(uuid.uuid4())
            first = self.redeem(code, request_id)
            self.assertEqual(self.store.validate_entitlement(first[1])["expires_at"], deadline)
            self.now = deadline - 0.25
            self.assertEqual(self.redeem(code, request_id), first)
            self.assertEqual(self.store.subscription(first[1]), NODES)
            self.now = deadline
            self.assert_invalid(self.redeem, code, request_id)
            self.assert_invalid(self.store.subscription, first[1])
            self.assert_invalid(self.store.validate_entitlement, first[1])
        self.assertEqual([row["uses"] for row in self.store.inventory("codes")], [1] * 6)

    def test_revoked_code_blocks_successful_retry(self):
        code = self.store.issue("demo")
        request_id = str(uuid.uuid4())
        self.redeem(code, request_id)
        self.store.revoke_code(server.digest(code))
        self.assert_invalid(self.redeem, code, request_id)
        self.assert_invalid(self.store.validate_entitlement,
                            self.store.token_for(server.digest(code), request_id))

    def test_per_token_revocation(self):
        code = self.store.issue("demo", max_uses=2)
        request_id = str(uuid.uuid4())
        _, first = self.redeem(code, request_id)
        _, second = self.redeem(code)
        self.store.revoke_token(server.digest(first))
        self.assert_invalid(self.store.subscription, first)
        self.assert_invalid(self.store.validate_entitlement, first)
        self.assert_invalid(self.redeem, code, request_id)
        self.assertEqual(self.store.subscription(second), NODES)
        self.assertEqual(self.store.validate_entitlement(second)["name"], "Demo group")
        self.assertEqual(self.store.inventory("codes")[0]["uses"], 2)

    def test_race_single_use_has_one_winner(self):
        code = self.store.issue("demo")
        barrier = threading.Barrier(8)

        def race(_):
            barrier.wait()
            try:
                return self.redeem(code)
            except server.ServiceError as exc:
                return exc.error

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(race, range(8)))
        self.assertEqual(sum(isinstance(value, tuple) for value in results), 1)
        self.assertEqual(results.count("invalid_code"), 7)
        self.assertEqual(self.store.inventory("codes")[0]["uses"], 1)

    def test_race_same_request_is_idempotent(self):
        code = self.store.issue("demo")
        request_id = str(uuid.uuid4())
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(lambda _: self.redeem(code, request_id), range(8)))
        self.assertTrue(all(value == results[0] for value in results))
        self.assertEqual(self.store.inventory("codes")[0]["uses"], 1)

    def test_distinct_invites_with_same_request_have_distinct_tokens(self):
        request_id = str(uuid.uuid4())
        first = self.redeem(self.store.issue("demo"), request_id)
        second = self.redeem(self.store.issue("demo"), request_id)
        self.assertNotEqual(first[1], second[1])

    def test_invalid_inputs(self):
        for value in ("SHORT", "CODE-☃-123", "loŋgcode123", "code abcde", "A" * 129, 123, None):
            with self.subTest(value=value), self.assertRaises(server.ServiceError):
                server.normalize_code(value)
        for value in ("no", uuid.uuid4().hex, 123, None, "{}"):
            with self.subTest(value=value), self.assertRaises(server.ServiceError):
                server.normalize_request_id(value)
        for token in ("", "x" * 43, "x" * 44, "../healthz", "☃", None, 123, [], {}):
            self.assert_invalid(self.store.subscription, token)
            self.assert_invalid(self.store.validate_entitlement, token)

    def test_group_validation_and_base64(self):
        for content in (b"", b"\n", b"not a subscription", b"{}", b"[]",
                        b"https://example.invalid/subscription", b"http://example.invalid:443",
                        b"custom://opaque", b"vless://", b"vless://white space",
                        b"x" * (server.MAX_SUBSCRIPTION + 1)):
            with self.subTest(content=content[:30]), self.assertRaises(ValueError):
                self.store.put_group("bad", "Bad", content)
        for name in ("", " ", "x" * 101, "line\nbreak"):
            with self.assertRaises(ValueError):
                self.store.put_group("bad", name, NODES)
        with self.assertRaises(ValueError):
            self.store.put_group("../bad", "Bad", NODES)
        self.store.put_group("demo", "Encoded", base64.b64encode(UPDATED))
        _, token = self.redeem(self.store.issue("demo"))
        self.assertEqual(self.store.subscription(token), UPDATED)

    def test_node_deduplication_preserves_first_occurrence_order(self):
        content = b"\n  " + NODES.rstrip() + b"  \n" + UPDATED + NODES + UPDATED
        for encoded in (content, base64.b64encode(content)):
            with self.subTest(base64=encoded != content):
                self.assertEqual(server.validate_nodes(encoded), NODES + UPDATED)

    def test_unique_node_limit_and_failed_update_preserves_group(self):
        nodes = [
            "socks://10.0.2.2:18081#Node-" + str(index)
            for index in range(server.MAX_NODES)
        ]
        accepted = ("\n".join(nodes) + "\n").encode()
        with_duplicates = accepted + (nodes[0] + "\n").encode() * 100
        self.assertEqual(server.validate_nodes(with_duplicates), accepted)
        self.assertEqual(server.validate_nodes(base64.b64encode(with_duplicates)), accepted)
        _, token = self.redeem(self.store.issue("demo"))
        rejected = accepted + b"socks://10.0.2.2:18081#Extra\n"
        for content in (rejected, base64.b64encode(rejected)):
            with self.assertRaisesRegex(ValueError, "500 unique"):
                self.store.put_group("demo", "Too many nodes", content)
        self.assertEqual(self.store.subscription(token), NODES)

    def test_node_length_limit_raw_and_base64(self):
        prefix = "socks://10.0.2.2:18081#"
        remaining = server.MAX_NODE_LENGTH - len(prefix)
        for label in ("a" * remaining, "😀" * (remaining // 2) + "a" * (remaining % 2)):
            line = prefix + label
            accepted = (line + "\n").encode()
            rejected = (line + "a\n").encode()
            for content in (accepted, base64.b64encode(accepted)):
                self.assertEqual(server.validate_nodes(content), accepted)
            for content in (rejected, base64.b64encode(rejected)):
                with self.assertRaisesRegex(ValueError, "16384 characters"):
                    server.validate_nodes(content)

    def test_additional_android_node_schemes(self):
        for scheme in ("socks4", "wireguard", "hysteria2", "hy2"):
            with self.subTest(scheme=scheme):
                content = (scheme + "://fixture@example.invalid:443#Demo\n").encode()
                self.assertEqual(server.validate_nodes(content), content)
                self.assertEqual(server.validate_nodes(base64.b64encode(content)), content)

    def test_finite_expirations_required(self):
        for days in (0, -1, float("inf"), float("nan"), 3651):
            with self.assertRaises(ValueError):
                self.store.issue("demo", expires_days=days)
            with self.assertRaises(ValueError):
                self.store.issue("demo", grant_days=days)
        for count in (0, -1, 1_000_001):
            with self.assertRaises(ValueError):
                self.store.issue("demo", max_uses=count)
        with self.assertRaises(ValueError):
            self.store.issue("missing")

    def test_private_permissions_and_safe_backup(self):
        code = self.store.issue("demo")
        first = self.redeem(code)
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for path in self.directory.glob("service.sqlite*"):
                self.assertEqual(path.stat().st_mode & 0o077, 0)
            self.store.backup(self.directory / "backup.sqlite")
        restored = server.Store(self.directory / "backup.sqlite", clock=lambda: self.now)
        self.assertEqual(restored.subscription(first[1]), NODES)
        self.assertEqual(restored.key, self.store.key)
        with self.assertRaises(FileExistsError):
            self.store.backup(self.directory / "backup.sqlite")
        os.chmod(self.store.path, 0o644)
        with self.assertRaises(ValueError):
            server.Store(self.store.path)
        os.chmod(self.store.path, 0o600)
        os.chmod(self.directory, 0o755)
        with self.assertRaises(ValueError):
            server.Store(self.store.path)
        os.chmod(self.directory, 0o700)
        (self.directory / "link.sqlite").symlink_to(self.store.path)
        with self.assertRaises(ValueError):
            server.Store(self.directory / "link.sqlite")


class HTTPTests(StoreCase):
    def setUp(self):
        super().setUp()
        self.httpd = server.InvitationServer(
            ("127.0.0.1", 0), self.store, "https://subscriptions.example.invalid",
            rate_limit=100, client_timeout=0.2,
        )
        self.thread = threading.Thread(target=self.httpd.serve_forever,
                                       kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        super().tearDown()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection(*self.httpd.server_address, timeout=2)
        try:
            conn.request(method, path, body, headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def post(self, code, request_id=None):
        return self.request("POST", "/v1/invitations/redeem",
                            json.dumps({"code": code, "request_id": request_id or str(uuid.uuid4())}),
                            {"Content-Type": "application/json"})

    def validate(self, token):
        return self.request("POST", "/v1/entitlements/validate", json.dumps({"token": token}),
                            {"Content-Type": "application/json"})

    def raw_request(self, data):
        with socket.create_connection(self.httpd.server_address, timeout=2) as sock:
            sock.sendall(data)
            response = http.client.HTTPResponse(sock)
            response.begin()
            return response.status, response.read()

    def test_emulator_smoke_fixture_and_android_token_url(self):
        nodes = (SERVICE_DIR / "examples" / "smoke-nodes.txt").read_bytes()
        self.assertEqual(nodes.strip(), b"socks://10.0.2.2:18081#Invitation-test")
        self.store.put_group("smoke", "Invitation test", nodes)
        self.httpd.public_base_url = server.validate_base_url("http://10.0.2.2:18084", True)
        status, _, raw = self.post(self.store.issue("smoke"))
        self.assertEqual(status, 200)
        result = json.loads(raw)
        self.assertRegex(result["subscription_url"],
                         r"^http://10\.0\.2\.2:18084/v1/subscriptions/[A-Za-z0-9_-]{43}$")
        path = urlsplit(result["subscription_url"]).path
        self.assertEqual(self.request("GET", path)[2], nodes)

    def test_http_contract_and_live_group_updates(self):
        code = self.store.issue("demo")
        request_id = str(uuid.uuid4())
        status, headers, raw = self.post(code, request_id)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        result = json.loads(raw)
        self.assertEqual(set(result), {"name", "subscription_url"})
        self.assertEqual(result["name"], "Demo group")
        self.assertTrue(result["subscription_url"].startswith("https://subscriptions.example.invalid/"))
        self.assertNotIn("example-password", raw.decode())
        self.assertEqual(self.post(code, request_id)[2], raw)
        self.assertEqual(self.post(code)[0], 404)
        path = urlsplit(result["subscription_url"]).path
        status, headers, body = self.request("GET", path)
        self.assertEqual((status, body), (200, NODES))
        self.assertEqual(headers["Content-Type"], "text/plain; charset=utf-8")
        self.store.put_group("demo", "Updated", UPDATED)
        self.assertEqual(self.request("GET", path)[2], UPDATED)
        self.store.revoke_code(server.digest(code))
        self.assertEqual(self.request("GET", path)[0], 404)
        self.assertEqual(self.post(code, request_id)[0], 404)

    def test_health_and_unknown_routes(self):
        self.assertEqual(json.loads(self.request("GET", "/healthz")[2]), {"status": "ok"})
        self.assertEqual(self.request("GET", "/admin")[0], 404)
        self.assertEqual(self.request("GET", "/healthz?foo=bar")[0], 404)
        self.assertEqual(self.request("PUT", "/healthz")[0], 400)
        self.assertEqual(self.request("GET", "/v1/subscriptions/..%2fhealthz")[0], 404)

    def test_entitlement_contract_and_both_expiries(self):
        for code_days, grant_days in ((1, 30), (30, 1)):
            with self.subTest(code_days=code_days, grant_days=grant_days):
                code = self.store.issue("demo", expires_days=code_days, grant_days=grant_days)
                request_id = str(uuid.uuid4())
                result = json.loads(self.post(code, request_id)[2])
                path = urlsplit(result["subscription_url"]).path
                token = path.split("/")[-1]
                deadline = self.now + 86400
                self.store.put_group("demo", "Renamed", UPDATED)
                status, headers, raw = self.validate(token)
                self.assertEqual(status, 200)
                self.assertEqual(headers["Cache-Control"], "no-store")
                self.assertEqual(json.loads(raw), {
                    "server_time": self.now, "expires_at": deadline, "name": result["name"],
                })
                self.now = deadline - 0.5
                self.assertEqual(self.validate(token)[0], 200)
                self.assertEqual(self.request("GET", path)[0], 200)
                self.now = deadline
                for response in (self.validate(token), self.request("GET", path),
                                 self.post(code, request_id)):
                    self.assertEqual(response[0], 404)
                    self.assertEqual(json.loads(response[2]), {"error": "invalid_code"})

    def test_entitlement_rejects_revoked_and_invalid_tokens(self):
        for revoke in (self.store.revoke_code, self.store.revoke_token):
            code = self.store.issue("demo")
            result = json.loads(self.post(code)[2])
            token = result["subscription_url"].split("/")[-1]
            revoke(server.digest(code if revoke == self.store.revoke_code else token))
            self.assertEqual(self.validate(token)[0], 404)
        for token in (None, "", "x" * 43, "x" * 42, "x" * 44, "☃", 123, {}, []):
            with self.subTest(token=token):
                status, _, raw = self.validate(token)
                self.assertEqual(status, 404)
                self.assertEqual(json.loads(raw), {"error": "invalid_code"})
        status, _, raw = self.request("POST", "/v1/entitlements/validate", "{}",
                                     {"Content-Type": "application/json"})
        self.assertEqual((status, json.loads(raw)), (404, {"error": "invalid_code"}))

    def test_entitlement_body_only_and_shared_request_limits(self):
        path = "/v1/entitlements/validate"
        token = json.loads(self.post(self.store.issue("demo"))[2])["subscription_url"].split("/")[-1]
        for method, url in (("GET", path), ("GET", path + "?token=" + token),
                            ("POST", path + "?token=" + token), ("POST", path + "/" + token)):
            self.assertEqual(self.request(method, url)[0], 404)
        for body in ('[]', 'null', '{', '{"token":"x","token":"x"}',
                     '{"token":"x","extra":1}', "x" * 1025):
            self.assertEqual(self.request("POST", path, body,
                                         {"Content-Type": "application/json"})[0], 400)
        self.assertEqual(self.request("POST", path, "{}",
                                     {"Content-Type": "text/plain"})[0], 400)
        self.httpd.limiter = server.RateLimiter(limit=2)
        self.assertEqual(self.validate(token)[0], 200)
        self.assertEqual(self.request("GET", "/healthz")[0], 200)
        status, headers, raw = self.validate(token)
        self.assertEqual(status, 429)
        self.assertEqual(headers["Retry-After"], "60")
        self.assertEqual(json.loads(raw), {"error": "rate_limited"})

    def test_invalid_expired_exhausted_and_token_expiry_http(self):
        self.assertEqual(self.post("UNKNOWN-CODE")[0], 404)
        code = self.store.issue("demo", expires_days=1)
        self.now += 86400
        self.assertEqual(self.post(code)[0], 404)
        code = self.store.issue("demo", grant_days=1)
        result = json.loads(self.post(code)[2])
        self.assertEqual(self.post(code)[0], 404)
        self.now += 86400
        self.assertEqual(self.request("GET", urlsplit(result["subscription_url"]).path)[0], 404)

    def test_rejects_invalid_json_and_contract(self):
        bodies = [b"{}", b"[]", b"null", b"{", b"\xff",
                  b'{"code":"ABCDEFGH","code":"ABCDEFGH","request_id":"x"}',
                  json.dumps({"code": "ABCDEFGH", "request_id": str(uuid.uuid4()), "extra": 1}).encode(),
                  json.dumps({"code": "ABCDEFGH", "request_id": "bad"}).encode(),
                  json.dumps({"code": None, "request_id": str(uuid.uuid4())}).encode()]
        for body in bodies:
            with self.subTest(body=body):
                status, _, raw = self.request("POST", "/v1/invitations/redeem", body,
                                             {"Content-Type": "application/json"})
                self.assertEqual(status, 400)
                self.assertEqual(json.loads(raw), {"error": "invalid_request"})

    def test_bounded_body_and_header_checks(self):
        status, _, _ = self.request("POST", "/v1/invitations/redeem", b"x" * 1025,
                                    {"Content-Type": "application/json"})
        self.assertEqual(status, 400)
        for headers in (
            "Content-Type: application/json\r\n",
            "Content-Type: text/plain\r\nContent-Length: 2\r\n",
            "Content-Type: application/json\r\nContent-Length: -1\r\n",
            "Content-Type: application/json\r\nContent-Length: 99999999999999\r\n",
            "Content-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 2\r\n",
            "Content-Type: application/json\r\nContent-Type: application/json\r\nContent-Length: 2\r\n",
            "Content-Type: application/json\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n",
            "Content-Type: application/json\r\nContent-Length: 2\r\nContent-Encoding: gzip\r\n",
            "Content-Type: application/json\r\nContent-Length: 2\r\nExpect: 100-continue\r\n",
        ):
            with self.subTest(headers=headers):
                status, body = self.raw_request(
                    ("POST /v1/invitations/redeem HTTP/1.1\r\nHost: localhost\r\n" +
                     headers + "\r\n{}").encode()
                )
                self.assertEqual(status, 400)
                self.assertEqual(json.loads(body), {"error": "invalid_request"})
        self.assertEqual(self.request("GET", "/" + "x" * 257)[0], 400)

    def test_slow_body_times_out(self):
        status, raw = self.raw_request(
            b"POST /v1/invitations/redeem HTTP/1.1\r\nHost: localhost\r\n"
            b"Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{"
        )
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw)["error"], "invalid_request")

    def test_concurrent_http_single_use_and_idempotent_retries(self):
        code = self.store.issue("demo")
        request_id = str(uuid.uuid4())
        with ThreadPoolExecutor(max_workers=6) as executor:
            results = list(executor.map(lambda _: self.post(code, request_id), range(6)))
        self.assertTrue(all(result[0] == 200 for result in results))
        self.assertTrue(all(result[2] == results[0][2] for result in results))
        self.assertEqual(self.store.inventory("codes")[0]["uses"], 1)
        code = self.store.issue("demo")
        with ThreadPoolExecutor(max_workers=6) as executor:
            statuses = list(executor.map(lambda _: self.post(code)[0], range(6)))
        self.assertEqual(statuses.count(200), 1)
        self.assertEqual(statuses.count(404), 5)

    def test_connection_capacity_is_bounded(self):
        self.httpd.slots = threading.BoundedSemaphore(1)
        self.httpd.slots.acquire()
        try:
            with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError)):
                self.request("GET", "/healthz")
        finally:
            self.httpd.slots.release()
        self.assertEqual(self.request("GET", "/healthz")[0], 200)

    def test_rate_limit_ignores_forwarded_headers(self):
        self.httpd.limiter = server.RateLimiter(limit=2)
        for address in ("1.2.3.4", "5.6.7.8"):
            self.assertEqual(self.request("GET", "/healthz", headers={"X-Forwarded-For": address})[0], 200)
        status, headers, raw = self.request("GET", "/healthz", headers={"X-Forwarded-For": "9.8.7.6"})
        self.assertEqual(status, 429)
        self.assertEqual(headers["Retry-After"], "60")
        self.assertEqual(json.loads(raw), {"error": "rate_limited"})

    def test_no_sensitive_access_or_error_logs(self):
        code = self.store.issue("demo")
        logs = io.StringIO()
        with redirect_stderr(logs):
            result = json.loads(self.post(code)[2])
            token = result["subscription_url"].split("/")[-1]
            self.request("GET", "/v1/subscriptions/" + token)
            self.validate(token)
            self.request("GET", "/" + code)
            self.request("GET", "/v1/subscriptions/" + token + "?sensitive")
            self.request("INVALID", "/" + code)
        self.assertEqual(logs.getvalue(), "")
        self.assertNotIn(code, logs.getvalue())
        self.assertNotIn(token, logs.getvalue())
        self.assertNotIn(NODES.decode(), logs.getvalue())

    def test_internal_error_has_generic_response_and_log(self):
        original = self.store.redeem
        sensitive = "SECRET-SUBSCRIPTION-CONTENT"

        def fail(*args):
            raise sqlite3.OperationalError(sensitive)

        self.store.redeem = fail
        logs = io.StringIO()
        try:
            with redirect_stderr(logs):
                status, _, body = self.post("UNKNOWN-CODE")
        finally:
            self.store.redeem = original
        self.assertEqual(status, 500)
        self.assertEqual(json.loads(body), {"error": "server_error"})
        self.assertNotIn(sensitive, logs.getvalue())


class ValidationTests(unittest.TestCase):
    def test_public_origin_validation(self):
        for value in ("https://example.invalid", "https://example.invalid:443/",
                      "https://127.0.0.1", "https://[::1]:443"):
            self.assertEqual(server.validate_base_url(value), value.rstrip("/"))
        for value in ("http://example.invalid", "ftp://example.invalid", "//example.invalid",
                      "https://user:pass@example.invalid", "https://example.invalid/a",
                      "https://example.invalid?x", "https://example.invalid#x",
                      "https://example.invalid?", "https://example.invalid#",
                      "https://example.invalid:0", "https://example.invalid:99999",
                      "https://example.invalid:", "https://example..invalid",
                      "https://[::1%zone]", "https://[::1]evil",
                      "https://", "https://example.invalid\\evil", "https://exa mple.invalid",
                      "https://example.invalid\n", "https://[bad", "https://exa%mple.invalid"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                server.validate_base_url(value, True)
        for value in ("http://10.0.2.2:18084", "http://127.0.0.1:18084", "http://localhost:18084",
                      "http://[::1]:18084"):
            with self.assertRaises(ValueError):
                server.validate_base_url(value)
            self.assertEqual(server.validate_base_url(value, True), value)
        with self.assertRaises(ValueError):
            server.validate_base_url("http://192.168.1.1", True)

    def test_rate_cache_bounded_and_expiring(self):
        now = [0]
        limiter = server.RateLimiter(limit=1, capacity=2, clock=lambda: now[0])
        self.assertTrue(limiter.allow("a"))
        self.assertFalse(limiter.allow("a"))
        self.assertTrue(limiter.allow("b"))
        self.assertFalse(limiter.allow("c"))
        self.assertEqual(len(limiter.entries), 2)
        now[0] += 60
        self.assertTrue(limiter.allow("c"))
        self.assertTrue(limiter.allow("a"))
        self.assertLessEqual(len(limiter.entries), 2)


class CLITests(StoreCase):
    def cli(self, *args):
        return subprocess.run(
            [sys.executable, str(SERVICE_DIR / "server.py"), "--db", str(self.store.path), *args],
            capture_output=True, text=True, timeout=5,
        )

    def test_seed_invite_revoke_and_backup_cli(self):
        nodes_file = self.directory / "nodes.txt"
        nodes_file.write_bytes(NODES)
        result = self.cli("group", "cli", "--name", "CLI demo", "--file", str(nodes_file))
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.cli("invite", "cli")
        self.assertEqual(result.returncode, 0, result.stderr)
        code = result.stdout.strip()
        # CLI uses wall clock; this Store's simulated earlier clock is safe here.
        _, token = self.redeem(code)
        grants = json.loads(self.cli("list", "grants").stdout)
        self.assertEqual(grants[0]["token_hash"], hashlib.sha256(token.encode()).hexdigest())
        result = self.cli("revoke-token", "--hash", grants[0]["token_hash"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_invalid(self.store.subscription, token)
        result = self.cli("revoke-code", "--hash", server.digest(code))
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.cli("backup", str(self.directory / "cli-backup.sqlite"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_help_and_unsafe_serve(self):
        self.assertEqual(self.cli("--help").returncode, 0)
        self.assertEqual(self.cli("serve", "--help").returncode, 0)
        self.assertEqual(self.cli("serve", "--public-base-url", "http://example.invalid").returncode, 1)


if __name__ == "__main__":
    unittest.main()
