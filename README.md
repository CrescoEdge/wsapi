# Cresco WSAPI

WebSocket API plugin — the external client entrypoint (control, data plane, log streaming) over `wss://…:8282`.

Part of the **[Cresco](https://github.com/CrescoEdge/agent)** edge-computing framework. See the
**[agent repository](https://github.com/CrescoEdge/agent)** for the full architecture, build, and run guide.

## Role

A Cresco plugin that exposes the fabric to external clients over secure WebSockets on port 8282: the control API (`/api/apisocket`), the data plane (`/api/dataplane`), and log streaming (`/api/logstreamer`). Every connection is authenticated: a per-user credential (HTTP Basic over TLS) or the legacy `cresco_service_key` header. This is the endpoint the Java and Python client libraries connect to.

## Security

### The TLS certificate

wsapi serves TLS 1.3/1.2 from a PKCS12 keystore in its plugin data directory
(`<agent data>/plugin-data/<wsapi plugin id>/`):

| File | What it is |
|---|---|
| `ws.keystore` | PKCS12, mode 0600. Key and signature from the fabric crypto policy: EC P-384 with `SHA384withECDSA` by default (`wsapi_tls_key_type=RSA`: RSA-3072 or larger, `SHA384withRSA`). Subject `O=<region>, OU=<agent>, CN=<plugin id>`; alternative names `localhost`, `127.0.0.1`, `::1`, this host's names and addresses, plus `wsapi_tls_san`. Valid `wsapi_tls_validity_days` (default 730). |
| `ws.keystore.pass` | The keystore password: 32 random bytes, mode 0600. wsapi refuses a password file other users can read. |
| `ws.crt.pem` | The certificate (public). Clients can trust it as a CA file. |
| `ws.fingerprint` | The certificate's SHA-384 and SHA-256 fingerprints (public). Clients can pin either. |

At every start wsapi logs `wsapi TLS certificate fingerprint sha384:... (sha256:...)` and rewrites the two public files.
The certificate stays the same across restarts, so pins keep working.

An existing keystore keeps loading, with these exceptions. A keystore from an older wsapi that was protected by the
published default password `cresco`, or whose file other users could read, must be presumed exposed: it is
**replaced** at start, and the log shows a WARNING with the old and the NEW fingerprint (clients must pin the new
one). `wsapi_tls_keep_legacy_keystore=true` keeps it instead (re-saved under a random password, 0600) and logs a
WARNING at every start until the setting is removed, when it is replaced. A JKS store or one under a configured
password of fewer than 12 characters is re-saved once as PKCS12 under a random password, with the same certificate. A
certificate below the crypto policy (an older wsapi's RSA-2048 / `SHA256withRSA`) is kept with a WARNING at every
start; `wsapi_tls_replace_noncompliant=true` replaces it. An expired certificate is replaced. A keystore that none of
its passwords opens is refused, and wsapi does not start: delete `ws.keystore` and `ws.keystore.pass` to make a new
one, then re-pin clients.

To serve a CA-issued certificate, set `wsapi_keystore_file` to a PKCS12 file and `wsapi_keystore_password` (or
`wsapi_keystore_password_file`, an owner-only file) to its password. wsapi never modifies it, and refuses it if the key
is weaker than RSA-3072 / EC P-384 (EC P-256 under the `fips140-3` crypto policy), its signature algorithm is not
allowed by the crypto policy, it is JKS, or it has expired.

### Authentication

Every upgrade request must authenticate, on every endpoint:

- **Per-user credentials**: `Authorization: Basic base64(user:password)`. Each user belongs to one tenant and holds one
  role, `admin` or `user`. A caller of another tenant than the agent's is refused (403), except an `admin` of a
  superuser tenant (`broker_superuser_tenants`). A message naming another tenant (`cresco_tenant`) is refused (403)
  under the same rule.
- **The service key** (legacy): the `cresco_service_key` header. It authenticates as `service-key`, an `admin` of
  the agent's tenant. A weak key is never accepted: one on a known-weak list (`12345`, `changeit`, ...), with fewer than
  8 distinct characters, or under about 128 bits (estimated from its length and character classes; 32 random hex
  digits, e.g. `openssl rand -hex 32`, are enough) is refused with an ERROR at start. Set
  `wsapi_service_key_enabled=false` to turn it off once every client has its own credential.

Passwords are stored as PBKDF2-HMAC-SHA-384 hashes (600,000 iterations, a 32-byte random salt per user) in
`ws.users` in the plugin data directory, or the file named by `wsapi_users_file`. The file must be owner-only (mode
0600 or 0400, owned by the agent's user); one wsapi cannot read, or that does not parse, lets no user log in until it
is fixed (the error names the line). wsapi re-reads it within a second of a change. Manage it with the tool in the
wsapi jar (the password is read from the terminal without echo, or as the first line of standard input; never from
the command line):

```bash
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool add    <ws.users> <user> <tenant> <admin|user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool passwd <ws.users> <user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool remove <ws.users> <user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool grant  <ws.users> <user> <stream|prefix*>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool revoke <ws.users> <user> <stream|prefix*>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool list   <ws.users>
```

Failed attempts are limited without letting the limits be turned against real users. A *source* is an IPv4 address or
an IPv6 /64.
- 10 failures from a source within 60 s lock it for 60 s (HTTP 429 with `Retry-After`), each repeat lockout doubling
  up to 15 min; a malformed header counts like any failure, against its source only. A user who has logged in from
  that source before and has not failed there since is not locked out by others' failures there.
- A user's success at a source forgives only that user's failures there: an attacker cannot reset its count against a
  victim by logging in with its own account in between.
- Per user name, failures give the (user, source) pair growing delays after 3 free failures (1 s, 2 s, 4 s, ... up to
  15 min), never a lock: the real user at an address of their own is never refused because of failures elsewhere.
  While a user name has 50 failures within the window from all sources, each new source gets one free failure.
- The service key also has a fabric-wide count: after 30 failures within the window from all sources together, sources
  that have not used the key successfully in the last day are refused key attempts, for growing periods.
- The tables are bounded (least recently used entries forgotten); a full table never refuses a newcomer.

Admission is fair and bounded. Everything that needs no PBKDF2 (refusals, malformed headers, the service key, a
remembered password) is decided on the network thread; an unremembered password waits for the bounded PBKDF2 pool, at
most `wsapi_auth_pending_per_source` per source at a time, so one source cannot fill it. A verified password is
remembered for `wsapi_auth_cache_seconds` (an HMAC under a per-process random key, never the password). A user may hold
at most `wsapi_max_connections_per_user` connections (`wsapi_max_connections_per_admin` for admins); more are refused
(429). wsapi never sends a `WWW-Authenticate` challenge, and refuses (403) a request carrying a browser `Origin` other
than wsapi's own address or one in `wsapi_allowed_origins`, so a web page cannot open wsapi with a browser's cached
credentials. Refusals are logged with the source, the user name and the reason; secrets never are.

**API requests.** A caller has at most `wsapi_rpc_max_inflight_user` (8) or `wsapi_rpc_max_inflight_admin` (32) RPCs in
flight across its connections; more are answered at once with status `429` (busy), and a full pool with `503`; an RPC
never runs on a network thread. A `user` asks for at most `wsapi_rpc_max_timeout_user_ms` (120 s) per RPC, an admin up
to an hour. A `user` may address only its agent's own region (an agent joins only a region controller of its own
tenant, so that is the one region known to be the user's tenant; the controller does not tell wsapi the tenant of
other regions): a message to another region, or to a global controller in another region, is refused (403).

**The log stream.** Only an admin (or the service key) may set log levels or log routing; a `user`'s configuration lines
are refused, and its log stream never carries lines below `Warn` (Info, Debug and Trace can carry message contents).
In every forwarded log line, message parameters keep their values only when they are on an allowlist of routing and
status names (`action`, `status`, `dst_region`, ...); every other parameter's value, whatever its name, is
`<redacted>`.

Every message an API client causes (API socket requests and log-stream configuration) is sent on behalf of that
client (`PluginBuilder.msgOutOnBehalfOf`): the controller stamps the verified caller `api:<tenant>/<user>` with the
client's role, and every receiver authorizes the action against it (`@CrescoAction(roles=...)`, see the controller's
`docs/caller-authorization.md`). wsapi removes the reserved caller parameters (`cresco_caller`,
`cresco_caller_tenant`, `cresco_caller_roles`) from what a client sends, so a client cannot forge a caller. wsapi takes
the agent's tenant from the controller; until the controller knows it, every client is refused (503). wsapi's own
`nettuning` action requires `infra` or `admin`.

| Config key | Default | Meaning |
|---|---|---|
| `wsapi_port` | 8282 | wss listen port |
| `wsapi_keystore_file` | (managed keystore) | operator PKCS12 keystore |
| `wsapi_keystore_password` / `_file` | (random, `ws.keystore.pass`) | keystore password; used only with at least 12 characters |
| `wsapi_tls_key_type` | `EC` | `EC` (P-384) or `RSA` |
| `wsapi_tls_rsa_bits` | 3072 | RSA size, at least 3072 |
| `wsapi_tls_validity_days` | 730 | lifetime of a generated certificate |
| `wsapi_tls_replace_noncompliant` | false | replace an existing certificate that is below the crypto policy |
| `wsapi_tls_san` | | extra subject alternative names, comma-separated |
| `wsapi_users_file` | `ws.users` in the plugin data directory | the credential file |
| `cresco_service_key` / `_file` | | the legacy service key |
| `wsapi_service_key_enabled` | true | accept the service key |
| `wsapi_auth_max_failures` | 10 | failures per source (IPv4 address, IPv6 /64) per window before a lockout |
| `wsapi_auth_max_user_failures` | 50 | failures per user name per window that mark a distributed guess |
| `wsapi_auth_window_seconds` | 60 | the counting window |
| `wsapi_auth_lockout_seconds` / `wsapi_auth_max_lockout_seconds` | 60 / 900 | first lockout, and the cap of the doubling |
| `wsapi_auth_cache_seconds` | 300 | how long a verified password is remembered; 0 turns it off |
| `wsapi_auth_threads` / `wsapi_auth_queue` | cores/2 (2-8) / 256 | authentication workers; a full queue answers 503 |
| `wsapi_auth_pending_per_source` | 2 | password checks one source may have waiting for the PBKDF2 pool |
| `wsapi_max_connections_per_user` / `wsapi_max_connections_per_admin` | 64 / 256 | open connections per caller |
| `wsapi_allowed_origins` | (none) | browser Origins allowed besides wsapi's own, comma-separated `https://host[:port]` |
| `wsapi_rpc_max_inflight_user` / `wsapi_rpc_max_inflight_admin` | 8 / 32 | RPCs in flight per caller |
| `wsapi_rpc_max_timeout_user_ms` | 120000 | longest RPC timeout a `user` may ask for |
| `wsapi_tls_keep_legacy_keystore` | false | keep a legacy keystore presumed exposed (WARNING at every start) |
| `wsapi_upgrade_timeout_seconds` | 30 | a connection that has not completed an authenticated WebSocket upgrade by then is closed |
| `wsapi_api_max_message_bytes` | 67108864 (64 MiB) | largest API-socket message; room for a plugin jar upload (base64, about 4/3 of the jar) |
| `wsapi_logstreamer_max_message_bytes` | 65536 (64 KiB) | largest log-stream message (configuration lines) |
| `wsapi_dataplane_max_message_bytes` | 67108864 (64 MiB) | largest dataplane message |

A message over its endpoint's cap, in one frame or in fragments, closes the connection with WebSocket status 1009
(message too big), its reason naming the setting. Every endpoint used to accept 1 GiB, buffered whole per connection.

### The dataplane

- **Only callers of the agent's tenant** may open a dataplane stream: the dataplane wsapi reads and writes is that
  tenant's. Anyone else (an admin of a superuser tenant included) gets the activation reply `status_code=9`.
- **A `user` sees only its own streams and streams granted to it.** Its streams are named `u:<user>:...` (ident_key
  `stream_name`); `CredentialTool grant` adds others (an exact name, or a prefix ending in `*`). A raw subscription must
  anchor `stream_name = '<such a stream>'`, so a user cannot subscribe to another caller's transfers or a process's
  output (`seq_num='0'`, `type='output'`, ...). A user writes `type=output` only on its own streams. Admins and the
  service key are not limited by ownership.
- **No stdin on the dataplane.** The executor takes a process's stdin only from its `write_stdin` action (a MsgEvent,
  accepted from the caller that started the runner, an admin or infra; the clients' `executor.write_stdin`). A
  handshake that would write stdin (`io_type_key=type` with `input_id` `input` or `input-*`) is refused for every
  caller, admins included, with that reason (`status_code=9`). It used to be accepted from admins, and the hardened
  executor would silently drop what it carried.
- **Reserved properties.** No client may name a property infrastructure writes as its `ident_key` or `io_type_key`:
  `cresco_*`, `transfer_*`, `seq_num`, `offset`, `total_bytes`, `sha384`, `start_byte`, `byte_length`, `dp_bytes`,
  `region_id`, `agent_id`, `plugin_id`, `pluginname`, `event`, `loglevel`, `logid`, `session_id`. Everything a client
  publishes carries its verified writer, `cresco_writer=api:<tenant>/<user>` and `cresco_writer_roles=<role>`.
- A refused handshake answers `status_code=9` with the reason; the next text frame is a new handshake, and the third
  refusal closes the connection.
- **A subscriber never receives a process's stdin.** Every subscription wsapi makes is the client's selector narrowed
  by `(type IS NULL OR (type <> 'input' AND type NOT LIKE 'input-%'))`, so no stdin frame an older executor or another
  publisher puts on the dataplane reaches a client.
- **Map events arrive as JSON text frames.** A dataplane `MapMessage` (for example the executor's `execution_log`,
  with the exit code, and `delete_exchange` when a process ends) reaches the client as a text frame with the JSON
  object of its entries (bytes as base64). wsapi used to drop them.
- **A streamfile transfer ends with a JSON text frame.** Chunks still arrive as binary frames,
  `[transfer_id][seq_num, 6 digits][payload]`; filerepo's terminal frame arrives as a text frame
  `{"transfer_id", "transfer_end": "true", "transfer_status", "transfer_desc", "total_bytes", "sha384", ...}`
  (`transfer_status` 10 complete, 9 cancelled, 8 failed). A client verifies the SHA-384 of what it reassembled before
  it uses it; both clients' `filerepo.stream_file` do.

### Operator steps when upgrading

1. Start the upgraded agent. A keystore from an older wsapi (protected by `cresco`, or readable by others) is replaced:
   the log shows the NEW fingerprint (or set `wsapi_tls_keep_legacy_keystore=true` to keep it, with a WARNING at every
   start). wsapi writes `ws.crt.pem` and `ws.fingerprint` in its plugin data directory. Check the ERROR log for a weak
   `cresco_service_key`: replace it with `openssl rand -hex 32` and give clients the new key.
2. Give every client the fingerprint from `ws.fingerprint` (or the log line), or a copy of `ws.crt.pem`. The clients
   (pycrescolib 1.4.0+, clientlib 1.3 hardening) verify the certificate on every connection by default and send
   nothing to a server they do not trust, so a client without one of these stops connecting:
   - Python (pycrescolib): `clientlib(host, port, service_key, fingerprint="sha384:...")`, or `ca_file="ws.crt.pem"`, or
     set `CRESCO_TLS_FINGERPRINT` / `CRESCO_TLS_CA_FILE` in the environment.
   - Java (clientlib): `CrescoClient.builder(host, port).serviceKey(key).pinnedFingerprint("sha384:...").build()`, or
     `.caFile(path)`, or `-Dcresco.client.tls.fingerprint=...` / `CRESCO_TLS_FINGERPRINT`.
3. Optional: set `wsapi_tls_replace_noncompliant=true` once to move an older certificate to the crypto policy's
   algorithms, and give clients the new fingerprint.
4. Optional: create per-user credentials with the `CredentialTool`, move clients to `user=` / `password=` (Python) or
   `.credentials(user, password)` (Java), then set `wsapi_service_key_enabled=false`.
