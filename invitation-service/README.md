# Portable invitation service

A dependency-free Python 3.9+ service for **invite code → managed subscription URL → proxy nodes**. Runs on a small Linux server, a private machine, or a VM; no cloud account/vendor SDK is needed. SQLite stores groups, invitations, and grants. Administration is local CLI only, not a public API.

This is a focused, modest-traffic, **single-host** first version, not a general public-facing web platform. Python's standard-library HTTP server is not a hardened Internet edge: **bind it to loopback behind a maintained HTTPS reverse proxy**. Do not expose port 18084 publicly.

## Local Android emulator demo

**Customer UI contains only the invitation code field and Redeem button. There is no visible or editable API address.** The operator embeds the origin when building Android with the Gradle property `-PINVITATION_SERVICE_URL`. The production default is the owner's existing HTTPS Alibaba IP endpoint with its trusted Let's Encrypt IP certificate.

Run from the repository root. Commands create a private `invitation-service/data/` directory automatically. The sample nodes use reserved `example.invalid` and fake credentials: they demonstrate importing a node, **not working proxy connectivity**.

```sh
python3 invitation-service/server.py group demo \
  --name "Invitation demo" --file invitation-service/examples/nodes.txt
python3 invitation-service/server.py invite demo
# Copy the one-time printed code into Android Add Server → invitation.

python3 invitation-service/server.py serve \
  --public-base-url http://10.0.2.2:18084 \
  --host 127.0.0.1 --port 18084 --allow-insecure-dev
```

For local tests, the **operator** builds/installs a debug APK with the emulator origin embedded:

```sh
# From the Android Gradle project directory; operator build step, not customer setup:
./gradlew assembleDebug -PINVITATION_SERVICE_URL=http://10.0.2.2:18084
```

The customer then enters only the printed code and taps Redeem. An emulator reaches the host via `10.0.2.2`; a host-side health check uses `curl http://127.0.0.1:18084/healthz`. For a physical device connected by USB, the operator can use `adb reverse tcp:18084 tcp:18084` and build the debug APK with `-PINVITATION_SERVICE_URL=http://127.0.0.1:18084`, also using that origin in the backend's `--public-base-url`. Do not distribute the local-test APK as the production build.

For an emulator import smoke test against a separately running local SOCKS proxy:

```sh
python3 invitation-service/server.py group smoke \
  --name "Invitation test" --file invitation-service/examples/smoke-nodes.txt
python3 invitation-service/server.py invite smoke
python3 invitation-service/server.py serve \
  --public-base-url http://10.0.2.2:18084 \
  --host 127.0.0.1 --port 18084 --allow-insecure-dev
```

The fixture contains only `socks://10.0.2.2:18081#Invitation-test`. No proxy is started by this service; importing can be tested without one, while proxy connectivity requires your own SOCKS listener on host port 18081. Subscription tokens are 43 URL-safe ASCII characters, with no padding, and use the configured origin plus `/v1/subscriptions/`.

Only the explicit dev flag permits HTTP, and only for `localhost`, loopback IPs, or `10.0.2.2`. Android debug permits local emulator/loopback HTTP; release requires HTTPS. This flag does not disable TLS checks, expose the listener, or make LAN HTTP acceptable. `--public-base-url` must otherwise be an HTTPS **origin**: no credentials, path prefix, query, or fragment. An optional trailing `/` is removed. URLs are built exclusively from that configured origin, never from request `Host`/forwarded headers.

```sh
python3 invitation-service/server.py --help
python3 invitation-service/server.py serve --help
python3 -m unittest discover -s invitation-service/tests -v
```

Tests use disposable, private `.test-data-*` directories inside this service tree and real loopback HTTP sockets on OS-assigned ports, and clean up afterward. They cover concurrency, idempotency, expiration/revocation, CLI, backups/permissions, live group updates, request limits, origin validation, rate limiting, and sensitive-log suppression. No Android builds or external services are needed.

## API and behavior

### Redeem

```http
POST /v1/invitations/redeem
Content-Type: application/json

{"code":"YOUR-GENERATED-INVITATION","request_id":"27f1ea2c-d849-4b69-a749-777b8e890894"}
```

Success, HTTP 200:

```json
{"name":"Invitation demo","subscription_url":"https://invite.example.com/v1/subscriptions/<opaque-token>"}
```

* `name` contains 1–100 characters. The URL is a service-owned capability; there is **no upstream subscription URL**.
* Generate a UUID once for a redemption attempt and retain the same code + UUID for every retry, including after app/process restart or a lost response. Successful repeated redemption returns the same name/token and consumes only one use. A newly generated UUID is a **different use**; it does not recover a lost single-use redemption. Treat the UUID as confidential recovery material along with the code.
* Outer ASCII whitespace is trimmed, then codes are uppercased; only ASCII letters/digits/hyphens, 8–128 characters, are accepted. Hyphens are significant. UUIDs must use canonical hyphenated form (uppercase hex accepted).
* Generated invitations have 160 random bits. Defaults: **one use, invitation expires in 30 days, grant duration at most 30 days**. No infinite-expiry or unlimited-use invitations.
* Invitations are ongoing authorization, not just redemption tickets. Effective authorization expiry is **`min(code.expires_at, grant.expires_at)`**. New grants are capped at invitation expiry; existing database grants are checked against both deadlines without migration. At or after either deadline, redemption retries, validation, and subscription reads return `invalid_code`. Idempotent retries survive usage exhaustion only, never expiry or revocation. Redeeming later cannot extend the invitation's deadline.
* `BEGIN IMMEDIATE` serializes checking expiry/usage, allocating a grant, and incrementing usage in one transaction. Concurrent different requests cannot oversubscribe; concurrent identical requests get the same token.
* SHA-256 code/token hashes, UUIDs, and a persistent 32-byte HMAC key are stored in SQLite. Plaintext invite codes are printed **only on issuance** and cannot be recovered from inventory. Tokens are derived via domain-separated HMAC from the key, code hash, and UUID; token plaintext is never stored.

Errors are JSON `{"error":"..."}`:

| HTTP | Error | Meaning |
| --- | --- | --- |
| 400 | `invalid_request` | Malformed code/UUID/JSON, invalid headers, too large a body, unsupported method |
| 404 | `invalid_code` | Unknown, expired, exhausted, or revoked invitation/grant |
| 404 | `invalid_request` | Unknown route |
| 429 | `rate_limited` | Peer limit or full rate-limit cache; includes `Retry-After: 60` |
| 500 | `server_error` | Unexpected internal/storage failure; no diagnostic details |

Expired/exhausted/revoked codes deliberately share `invalid_code` to reduce enumeration; the service does not emit separate `expired_code` or `exhausted_code` errors. Reverse-proxy failures and connection-level rejection/timeouts may return a non-JSON error or no response; clients should treat them as transport errors and preserve the UUID.

Redemption and entitlement-validation bodies are bounded to 1,024 bytes. Exactly one numeric `Content-Length` and one JSON `Content-Type` are required; `application/json; charset=utf-8` is also accepted. Chunked transfer encoding, content encoding, `Expect`, duplicate JSON keys, and extra JSON fields are rejected. Only exact paths are routed (no query parameters).

### Ongoing entitlement validation

```http
POST /v1/entitlements/validate
Content-Type: application/json

{"token":"<43-character-base64url-token>"}
```

Success, HTTP 200, `Cache-Control: no-store`:

```json
{"server_time":1790228695.0,"expires_at":1795499095.0,"name":"Invitation demo"}
```

* Both times are **Unix seconds**, JSON numbers (fractional seconds are allowed), not milliseconds or local-time strings. `server_time` is sampled from the backend clock used for the authorization check; `expires_at` is the effective minimum deadline. The backend clock is authoritative and must be kept synchronized.
* `name` is the original name captured at redemption, even if the group was renamed. No node credentials, nodes hash, or new token is returned, and validation neither consumes a use nor extends expiry. The redemption request/result contract remains unchanged.
* Extract the token from the service-owned subscription URL's final path component. Send it **only in the JSON body**, never in query parameters or validation URL paths. Do not log request bodies. There is no GET validation endpoint.
* Unknown, missing (`{}`), null, malformed, expired, or revoked tokens return HTTP 404 `{"error":"invalid_code"}`. Revocation of either code or grant denies access. Invalid JSON, non-object bodies, extra fields, and invalid headers return HTTP 400 `invalid_request`. Rate limits and generic errors are identical to the other routes.
* The app must validate online at **every app start and before every subscription update**, and before enabling invitation-managed connections. Persist the token and entitlement association with managed nodes so a restart cannot turn them into unmanaged, usable nodes. Re-check expiry while running and stop/disable managed access when it expires.
* **Fail closed:** unavailable network, timeouts, TLS errors, 429/5xx, malformed responses, missing entitlement state, or any other validation failure must not unlock cached managed nodes or retain an active managed connection. A saved successful response is not authorization for another app start; there is no offline grace period. Preserve recovery state for retry, but keep access disabled until fresh validation succeeds.
* During an already validated session, use the returned remaining duration with a monotonic clock, conservatively accounting for request elapsed time. Do not use a user-adjustable device wall clock to extend access. At expiry disable access even offline; process/device restart requires fresh server validation. Validation is point-in-time, not a revocation push channel; subscription reads independently recheck both deadlines and revocations.
* These are application authorization requirements, **not proxy credential revocation**. Previously downloaded/exported credentials used outside the app cannot be invalidated by this API or the frontend. Actual network revocation requires disabling/rotating credentials at the proxy server.

### Subscription and health

Subscription reads perform the same authorization checks as validation: both code and grant must exist, be unrevoked, and not yet expired. The effective grant deadline is always the minimum of both expiries, including for pre-upgrade tokens.

`GET /v1/subscriptions/<token>` returns UTF-8, newline-separated node URIs (`text/plain`), compatible with v2rayNG. Invalid/expired/revoked tokens return HTTP 404 `invalid_code`. Responses use `Cache-Control: no-store`. Bearer URLs must remain private: anyone holding one can download the same nodes while the grant remains valid.

Updating the associated group changes the nodes returned by **all existing tokens** immediately. Names returned on retries remain the original successful name for idempotency; new grants get the updated name.

`GET /healthz` returns `{"status":"ok"}` only. This proves the listener responds, not that every disk/database operation is healthy.

## Local administration

Use the **same database path and OS account** for the service and CLI. `--db` is a global option, placed before the command. The default is `invitation-service/data/service.sqlite` (relative to the script location, not the shell's working directory).

```sh
# Create or replace a group's local node content and display name:
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite \
  group team --name "Team servers" --file /private/nodes.txt

# Invitation and all its grants expire in 7 days (30-day grant cap cannot extend it):
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite \
  invite team --max-uses 10 --expires-days 7 --grant-days 30

python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite list groups
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite list codes
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite list grants

# Use the 64-character hash from the local inventory, not a plaintext secret:
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite \
  revoke-code --hash CODE_HASH_FROM_LIST
python3 invitation-service/server.py --db /var/lib/invitation-service/service.sqlite \
  revoke-token --hash TOKEN_HASH_FROM_LIST
```

`list codes` includes group, usage, expiry, and revocation; `list grants` includes token hash, code hash, stored grant expiry, and revocation. Expiries are Unix timestamps (seconds). For legacy grants, the stored expiry may exceed the code expiry: the API always enforces the minimum of both. No public listing, registration, group management, or admin authentication endpoints exist. A token hash can be computed locally from a known token with SHA-256; avoid putting the token in shell history. Revocation is irreversible here and never refunds an invitation use.

**Revoking a code disables every token created from it**, including idempotent retries. Revoking a token only disables that grant. Expiration/revocation prevent future subscription fetches; they **cannot erase previously downloaded proxy credentials or stop their use**. Rotate/disable credentials at the actual proxy server to revoke network access. User-specific subscription tokens do not make the underlying shared proxy credentials user-specific.

Group input is a local file of raw node URI lines or a standard base64-encoded subscription, up to 512 KiB. After decoding, surrounding whitespace is trimmed from each line and empty lines are ignored. Identical node lines are deduplicated, preserving their first-occurrence order. The service rejects **more than 500 unique nodes** or any trimmed node line **longer than 16,384 characters** (UTF-16 code units, matching Android's `String.length`). These limits apply to both raw and base64 input; validation failure leaves the previous group unchanged.

Recognized schemes: `ss`, `vless`, `vmess`, `trojan`, `socks`, `socks4`, `socks5`, `wireguard`, `hysteria2`, `hy2`. Validation checks supported URI-line shape, not protocol-specific reachability or every core configuration option. HTTP/HTTPS lines are intentionally **not supported**, so an upstream subscription URL cannot accidentally become node content. Full-core/custom JSON, arbitrary schemes, and remote fetching are not supported. The Android/core importer performs final node validation. No remote fetching also avoids a subscription-fetching SSRF surface.

## Production deployment

### Using your existing Alibaba Cloud ECS server

Use your **existing ECS instance**; no new paid instance, load balancer, database, or Cloudflare service is needed. The current deployment reuses the HTTPS IP endpoint and trusted Let's Encrypt IP certificate already served by nginx under **`nebula-web.service` on port 443**. Run the invitation backend as a separate daemon. These are operator instructions only; no remote operations are performed by this documentation.

* Create the dedicated `invitation` OS user and private `/var/lib/invitation-service` directory using the installation steps below. Run both CLI and service as that user; keep the directory **0700** and SQLite/sidecar files **0600**. Store code separately under root-owned `/opt/invitation-service`.
* Keep Python bound to **`127.0.0.1:18084`**. Check existing listeners before selecting a port; do not stop other applications to free one.
* Extend the **existing nginx HTTPS server** with narrowly scoped locations: exact `/v1/invitations/redeem` and `/v1/entitlements/validate` paths, plus `/v1/subscriptions/<token>` paths. Use [`deploy/nginx-locations.conf`](deploy/nginx-locations.conf). Forward those requests unchanged to `127.0.0.1:18084`, with request limits/timeouts and token-safe logging. Preserve every unrelated route and the existing certificate/listener; do not replace `location /`. Check backend health locally, or expose `/healthz` only after confirming it does not conflict with the existing site. Back up the configuration, validate using the existing nginx installation's configuration/prefix, and reload gracefully through its established service procedure. **Do not install/start Caddy or replace `nebula-web.service`.**
* Set the backend's `--public-base-url` to the **same trusted HTTPS IP origin embedded in the production Android build**. The existing valid IP certificate means a new domain is not required for this deployment; never disable certificate verification. If the operator later chooses a subdomain, configure DNS/certificate coverage and update both the backend origin and the APK's build-time `-PINVITATION_SERVICE_URL` together. Confirm HTTPS before distributing that APK; customers never configure the address.
* For this service, permit public TCP **80/443 only** in the ECS security group and host firewall. Restrict SSH to your administrator IP/CIDR, including IPv6 rules where applicable; preserve your working administration access. **Never expose 18084 publicly.** Review shared rules without deleting access required by unrelated applications.
* If the ECS instance is in **mainland China**, public domain hosting may require completed MIIT ICP filing (备案), domain real-name verification, and Alibaba Cloud access-provider prerequisites before access is enabled. Confirm the current requirements for your region, account, domain, and service with Alibaba Cloud; Hong Kong/overseas regions generally have different prerequisites. DNS and a TLS certificate alone do not satisfy filing requirements.

### Installation and reverse-proxy samples

Use [`deploy/invitation-service.service`](deploy/invitation-service.service) for the separate Python daemon, replacing its placeholder origin with the existing trusted HTTPS IP origin. Keep backend 18084 private. [`deploy/Caddyfile`](deploy/Caddyfile) remains an **alternative standalone example for other hosts only**, not part of this nginx/Alibaba deployment. On the shared nginx proxy, configure token-safe logging for the new locations without disabling unrelated applications' logs.

Example installation on existing systemd Linux (run installation as an administrator):

```sh
sudo useradd --system --home-dir /var/lib/invitation-service --shell /usr/sbin/nologin invitation
sudo install -d -o root -g root -m 0755 /opt/invitation-service
sudo install -o root -g root -m 0644 invitation-service/server.py /opt/invitation-service/server.py
sudo install -d -o invitation -g invitation -m 0700 /var/lib/invitation-service
sudo install -o root -g root -m 0644 invitation-service/deploy/invitation-service.service \
  /etc/systemd/system/invitation-service.service
# Edit the unit's public origin to match the production APK's embedded HTTPS origin.
# Add only the invitation API locations to the existing nginx configuration.
# Preserve nebula-web.service, its HTTPS listener/certificate, and unrelated routes.
sudo systemctl daemon-reload
sudo systemctl enable --now invitation-service
```

Seed/update groups as `sudo -u invitation python3 /opt/invitation-service/server.py --db /var/lib/invitation-service/service.sqlite ...`. Stage your local node file somewhere readable only by that user (0600), then remove it if no longer needed. Validate and gracefully reload the existing nginx service after adding the scoped locations. There is no production TLS listener inside Python and no new Caddy installation.

Operational constraints:

* One service process on one host, local SQLite disk (not NFS/network filesystems). No distributed rate limiting, automatic cleanup, replication, or horizontal-scaling guarantee. Redeeming writes serially and waits up to five seconds on database contention.
* Default maximum **32 concurrent connections**, five-second socket **idle** timeout, and 256-character API paths. Python also bounds individual header lines at 64 KiB and headers to 100. Configure tighter request limits and header/body timeouts at the existing nginx edge. Idle timeouts alone do not defeat clients continuously trickling data; use additional edge controls if needed for sustained slow-client abuse. Excess backend connections are closed rather than queued indefinitely.
* Default rate limit: **30 requests per TCP peer per minute across all routes**, including health/subscriptions/entitlements. The in-memory cache holds at most 4,096 active peers and fails closed for additional peers until entries expire. Counters reset on restart. Tune `--rate-limit` for your expected traffic and health checks. Budget for validation plus subscription fetch on each update; avoid retry storms when access fails closed.
* For the existing deployment, retain the operator-configured **`--rate-limit 300`** backend aggregate limit (the deployed service already uses 300); do not reset it to the code default during upgrade. The nginx sample keeps redemption/subscription requests in the shared `aigateway_invite` zone at **10 requests/minute per client IP, burst 5**, and puts validation alone in `aigateway_entitlement` at **60 requests/minute per client IP, burst 10**. Define both zones in the existing `http {}` block. This separates every-minute entitlement checks plus start/update checks from the tighter redemption/subscription budget, reducing false rejections for six active users sharing one NAT address. Limits remain per IP, not per user; monitor aggregate demand and tune deliberately.
* `Forwarded` and `X-Forwarded-For` are **never trusted**. With a loopback reverse proxy, all requests share that proxy's peer limit. An untrusted header cannot bypass it. This is a coarse backend safety limit, not a complete per-user anti-abuse system. Configure the existing nginx edge's rate limiter or a firewall for per-client/global abuse protection.
* Backend access logs are disabled. Internal errors produce only a fixed message, not a traceback, URL, code, body, or node content. The sample Caddy configuration disables its logs too, including structured error logs that can otherwise contain token paths. Do not enable raw proxy/CDN/access/APM logs or URL/body tracing. If you need monitoring, use counters/health probes or explicitly tested redaction, not full URLs. Never paste credentials into bug reports.
* Protect bearer URLs from browser history, analytics, screenshots, and shared clipboard/log collectors. Do not send codes/tokens in monitoring query strings.

## Permissions, persistence, and backup

### Upgrading an existing installation

Back up the live database with the existing local backup command, replace only the backend code, retain the configured database path/HMAC key, and restart the backend. Add the exact entitlement nginx location, check the existing nginx configuration, and reload it gracefully before distributing clients that require validation. These are operator steps, not automatic deployment actions.

**No SQLite schema migration, data reset, code reissuance, or grant rewriting is required.** Existing tokens and successful idempotent retries remain stable until their effective expiry. Old grants whose stored deadline exceeds invitation expiry now intentionally stop at invitation expiry. Do not roll back to the old backend after this change: it would restore the old expiry bypass.

The six existing one-use `private-subscription` invitations issued at **2026-09-24 13:44:55 +08:00**, with `--expires-days 61 --grant-days 61`, keep their fixed **2026-11-24 13:44:55 +08:00** deadline (`1795499095` Unix seconds). No shorter default duration is applied on startup or upgrade. Assuming no revocation or earlier grant expiry, redeemed entitlements remain valid strictly before that deadline, including late redemption; at the deadline all become invalid. Unredeemed codes remain redeemable before it subject to their one-use limit. Tests reproduce these timestamps using six synthetic codes only: never redeem, revoke, delete, modify, or reissue production codes to smoke-test the upgrade. Use `/healthz`, an absent/invalid token validation request, and separate disposable test invitations if needed.

The database parent must belong to the running account and have **0700** permissions. Database, WAL (`-wal`), SHM (`-shm`), and rollback journal (`-journal`) files must be regular files owned by that account with no group/other permissions (normally **0600**); symlinks are refused. New files are private; the CLI uses `umask 077`. This is a POSIX deployment (Linux/macOS); Windows ACL management is not implemented. Keep parent directories trusted and not writable by other accounts. Source code should be root-owned and non-writable by the runtime account.

SQLite contains **plaintext proxy nodes/credentials and the HMAC key** as well as hashes. Disk access is privileged access; it is not encrypted by this service. Use encrypted disks/backups as needed. Never commit `data/`, databases, backups, exported nodes, or real invitation codes.

Make a consistent online backup through SQLite's backup API:

```sh
sudo install -d -o invitation -g invitation -m 0700 /var/lib/invitation-service/backups
sudo -u invitation python3 /opt/invitation-service/server.py \
  --db /var/lib/invitation-service/service.sqlite \
  backup /var/lib/invitation-service/backups/snapshot-2026-09-24.sqlite
```

Backup destinations must be new files in private directories; existing files are never overwritten. The backup includes groups, counters, grants, revocations, and the persistent token key, so tokens continue working after restoration. Transfer it through a protected channel and apply a retention policy. Test restores.

**Do not copy only the live `.sqlite` file** while WAL writes are active. Restore with the service stopped: preserve the old database and its WAL/SHM together for rollback, place the complete backup at the configured database path with owner `invitation` and mode 0600 in its 0700 directory, and ensure no WAL/SHM from the old database remains alongside the replacement. Then restart and test. Restoring an old backup rolls back usage and revocations; account for that before reopening the service.

Do not delete/regenerate the stored HMAC key: it is needed for retry-safe token reconstruction. Keep the configured public origin stable and preserve the database across deployment. Changing origin affects future redemption results and old bearer URLs require a controlled migration; this first version does not migrate them automatically.
