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

### The production profile: client certificates only

`security_profile=production` (the setting the controller reads too; as `-Dsecurity_profile=production`, the environment
variable `CRESCO_security_profile` or the agent's configuration) is for secure deployments, where every certificate is
issued and assigned in advance. Any other value, or none, is the debug profile: everything below works as described.

- **Mutual TLS on every endpoint** (API socket, dataplane, log stream): a client must present a certificate that chains
  to a CA of `wsapi_client_ca` (a PEM bundle; intermediates included) and is within its validity dates, or TLS refuses
  the connection before any HTTP is read.
- **The certificate is the caller**, from its subject alternative names (URIs), set by the CA that issues it:
  exactly one `cresco:api:<tenant>/<user>` (the caller `api:<tenant>/<user>`), exactly one `cresco:role:admin` or
  `cresco:role:user`, and any number of `cresco:grant:<stream or prefix*>` (dataplane streams granted besides the user's
  own `u:<user>:...`). A certificate without a caller or a role, or with two, is refused (403, the reason in the log).
  Tenant rules, roles, connection and RPC limits apply as to any caller. For example, with OpenSSL:
  `-addext "subjectAltName=URI:cresco:api:default/gaiakeep,URI:cresco:role:admin"`.
- **Passwords and the service key are refused** (401, "disabled (security_profile=production)"), even over a valid
  certificate. **API tokens are refused** too, unless `wsapi_allow_tokens=true`; then a token works over a client
  certificate (TLS still requires one) and the caller is the token's user.
- **Revocation**, re-read within a second of a change and applied to open connections as well (closed with 1008, like
  a revoked token): a deny list, `wsapi_client_deny_file` (one SHA-256 certificate fingerprint per line, hex with or
  without colons, `sha256:` optional, `#` comments), and a CRL file, `wsapi_client_crl` (PEM or DER, every CRL signed by
  a CA of `wsapi_client_ca`). Neither may be writable by anyone but its owner. A list that cannot be read, does not
  parse, (a CRL) does not verify, or becomes writable by others accepts no client certificate at all until it is fixed
  (fail closed), and the open certificate connections close. A certificate that expires while connected is closed when it expires.
  A CRL past its next update still applies, with an ERROR at start.
- **wsapi does not start** in production (an ERROR `wsapi NOT started: security_profile=production: ...`) when
  `wsapi_client_ca` is missing, unreadable or holds no certificate, when a configured deny list or CRL cannot be used,
  or when the service key is configured and not disabled (`cresco_service_key` set without
  `wsapi_service_key_enabled=false`). The users of the credential file cannot log in with a password; a WARNING at start
  says so, and whether their tokens work.
- Clients: pycrescolib `clientlib(host, port, client_cert="client.pem", client_key="client.key", fingerprint=...)` (or a
  PKCS12 file as `client_cert`, `client_key_password=`; or `CRESCO_TLS_CLIENT_CERT`, `CRESCO_TLS_CLIENT_KEY`,
  `CRESCO_TLS_CLIENT_KEY_PASSWORD`); clientlib `CrescoClient.builder(host, port).clientCertificate(cert, key, password)`
  or `.clientCertificatePkcs12(p12, password)`, with the same environment variables. Both keep their server trust
  (`fingerprint` / `CRESCO_TLS_FINGERPRINT`, or a CA file).

| Config key | Default | Meaning |
|---|---|---|
| `security_profile` | (debug) | `production`: client certificates only |
| `wsapi_client_ca` | | production: the client CA bundle (PEM); required |
| `wsapi_client_deny_file` | | production: revoked client certificates by SHA-256 fingerprint |
| `wsapi_client_crl` | | production: CRLs of the client CAs (PEM or DER) |
| `wsapi_allow_tokens` | false | production: accept API tokens (over a client certificate) |

### Authentication

Every upgrade request must authenticate, on every endpoint (in the debug profile; production accepts client
certificates only, above):

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
is fixed (the error names the line). wsapi re-reads it within a second of a change, and checks its owner and mode (and
its directory's) every second as well: a file that becomes group- or world-readable, or a directory others can write,
is refused within a second, which also closes the open per-user connections (a chmod alone used to go unnoticed until
the content next changed). Manage it with the tool in the
wsapi jar (the password is read from the terminal without echo, or as the first line of standard input; never from
the command line):

```bash
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool add    <ws.users> <user> <tenant> <admin|user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool passwd <ws.users> <user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool remove <ws.users> <user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool role   <ws.users> <user> <admin|user>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool grant  <ws.users> <user> <stream|prefix*>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool revoke <ws.users> <user> <stream|prefix*>
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool list   <ws.users>
```

Failed attempts are limited so that strangers can slow real users at most, never lock them out, except where they share
the user's network (the limit below). A *source* is a bucket and an
exact address: the bucket is an IPv4 address or an IPv6 prefix (a /64 by default, `wsapi_auth_ipv6_prefix_bits`; set
128 where one /64 is shared by unrelated clients, e.g. at a hosting provider). An IPv4 client behind NAT64 (64:ff9b::/96,
64:ff9b:1::/96) or IPv4-mapped is its IPv4 address, not one /64 shared by every IPv4 client of the NAT64.
- **Known-good sources.** A user's successful login remembers that user's exact address and its bucket for
  `wsapi_auth_known_good_days` (30). Each user's set holds at most 64 entries and evicts only its own oldest, so no user
  can push another's out. They are kept across restarts in the plugin data directory (`ws.auth.known`, with its key in
  `ws.auth.known.key`), as HMAC-SHA-256 ids under a per-installation key, so the file names no user and no address. Both
  are read only as owner-only files (mode 0600/0400, owned by wsapi's user, in a directory no one else can write, on a
  filesystem with POSIX permissions); anything else is not trusted (wsapi starts with none and says so). An exact
  known-good address is never delayed by anyone else's failures.
- **Per bucket**, 10 failures within 60 s lock it for 60 s (HTTP 429 with `Retry-After`), each repeat lockout doubling
  up to 15 min (exact known-good addresses excepted); a malformed header counts here only. A user's success forgives
  only that user's own failures there.
- **Per (user, address)**, growing delays after 3 free failures (1 s, 2 s, 4 s, ... up to 15 min) for that address only.
- **A user's unknown sources: fair shares, then delays.** Each *share source* (an IPv4 /24,
  `wsapi_auth_share_ipv4_prefix_bits`; the IPv6 bucket) gets its own fair share of the user's budget of
  `wsapi_auth_max_user_failures` (50) checks per minute: while N share sources are failing for that user in the
  window, one is checked while its own failures plus one are at most 50 / N, and all of them together at most 100.
  One stranger's sources never use up another source's share, so a stranger guessing from one network never slows
  the real user at another. Beyond its share a source is delayed, not refused: its next check comes after
  `wsapi_auth_delayed_check_seconds` (30 s), doubling with each failed delayed check up to 15 min, and a success resets
  it. So strangers on **other** networks can only slow a user's password login from a new place (at worst to one check
  per 30 s), never stop it.
  **The bound:** one user's password gets at most 100 fair-share checks a minute (144,000 a day) plus about 100
  delayed checks a day for each distinct /24 (or IPv6 bucket) a guesser uses; with 10,000 networks, about 1.1 million
  guesses a day. The password policy below enforces a minimum length, a minimum number of different characters and an
  estimated strength of about 60 bits (from length and character classes), and refuses a short list of well-known
  passwords; it does not recognise every common password.
- **The limit: someone sharing your network.** An attacker on the same network as a user (the same IPv4 address behind a
  NAT, or the same IPv6 /64, e.g. one LAN or one carrier-grade NAT) can keep delaying that user's **password** logins
  from addresses not yet known good there (a new device, today's IPv6 privacy address), for as long as it keeps sending
  wrong passwords for that user: by address the attacker and the user cannot be told apart. The user's exact known-good
  addresses are not affected, and neither are API tokens. Use **API tokens** (never throttled per user, accepted from
  any address) for tools and automation, and for people who log in from shared networks (a NAT, a campus, a hosting
  provider; or set `wsapi_auth_ipv6_prefix_bits=128` where one /64 is shared by unrelated clients).
- **Passwords** must have at least 12 characters, 6 different ones and about 60 bits (estimated from length and
  character classes), and may not be a well-known password (or a well-known word with a few digits around it), the user
  name, or a run like `abcdefghijkl`. `CredentialTool` refuses others; at login a password that does not meet the policy
  (one set before it, or written into the file by hand) is refused with that reason in the log, until it is changed
  with `CredentialTool passwd`. The checks see through how characters look: the password is NFKC-normalised
  (full-width and mathematical letters become ASCII), accents are dropped, and letters that look like ASCII ones
  (Cyrillic `а с е о р х`, Greek `α ο ρ`, ...) are compared as those, so `раsswоrd1234` with Cyrillic letters is the
  well-known `password1234`. Outside ASCII the strength estimate is conservative: a letter of any script counts as one of
  26 letters of its case, any other character as one of 33 symbols (it used to count as one of 100, so `абвгдеабвгде`
  passed where `abcdefabcdef` did not). A non-ASCII password accepted before may now be refused at login, with the reason
  in the log; set a new one with `CredentialTool passwd`.
- **API tokens, for tools.** `CredentialTool token add <ws.users> <user> [--expires-days N] [--label L]` prints a token
  `cwt1_<id>.<secret>` (256 random bits) once; only its SHA-384 is stored (a `+token:` line of the credential file).
  Clients send it as `Authorization: Bearer <token>` (pycrescolib `token=`, clientlib `Credentials.token(...)`, or
  `CRESCO_API_TOKEN`). A token acts as its user (tenant, role, grants), may expire, and is revoked with
  `token revoke`; removing a user removes its tokens. It cannot be guessed, so a valid token is always accepted: never
  delayed or locked out by strangers guessing its user's password, nor by a guesser behind the tool's own address.
  Wrong and expired tokens count against their source (and are answered 429 while it is locked). **Tools
  and automation (GaiaKeep included) should use tokens**, not passwords or the shared service key.
- **The service key** (at least 128 bits; weaker keys are refused) is limited per bucket and per address, never by a
  fabric-wide gate. Wrong keys are a metric (`wsapi.auth.servicekey.failures`) and, at 30 or more within a window from
  anywhere, an ERROR `wsapi ALARM: ... wrong service keys ... someone is guessing it` (once per window).
- The tables are bounded (least recently used entries forgotten); a full table never refuses a newcomer.
  `wsapi.auth.failures` and `wsapi.auth.refused` count failures and 429s.

Admission is fair and bounded. Everything that needs no PBKDF2 (refusals, malformed headers, the service key, a
remembered password) is decided on the network thread; an unremembered password waits for the bounded PBKDF2 pool, at
most `wsapi_auth_pending_per_source` per source at a time, so one source cannot fill it. A verified password is
remembered for `wsapi_auth_cache_seconds` (an HMAC under a per-process random key, never the password). A user may hold
at most `wsapi_max_connections_per_user` connections (`wsapi_max_connections_per_admin` for admins); more are refused
(429, the reason naming the cap). The service key's connection and RPC limits count per source host
(`wsapi_service_key_limits_per_source`, on by default): every tool on every host that shares the key used to share one
admin budget, so one busy tool could starve the others. wsapi never sends a `WWW-Authenticate` challenge, and refuses (403) a request carrying a browser `Origin` other
than wsapi's own address or one in `wsapi_allowed_origins`, so a web page cannot open wsapi with a browser's cached
credentials. Refusals are logged with the source, the user name and the reason; secrets never are.

**Revocation reaches open connections.** A connection lives only while the credential it authenticated with would
still be accepted, as the same caller. Every open API, dataplane and log-stream connection is checked again every
`wsapi_revocation_check_ms` (1 s; 250 ms to 5 s), and every message a client sends is checked before it is served, so
within about 2 s of a change to the credential file (wsapi re-reads it within a second):
- `token revoke` closes every connection opened with that token, and an expired token's connections close when it expires;
- `remove` closes every connection of that user, its tokens' included;
- `passwd` closes the user's password connections (its tokens are separate credentials and keep theirs; revoke them too
  if the password leaked);
- a changed role, tenant or stream grants (`role`, `grant`, `revoke`, or an edit) closes every connection of that user,
  password and token alike, so the client reconnects with its new rights;
- a changed or disabled service key closes the service key's connections, and a caller of another tenant whose tenant is
  no longer in `broker_superuser_tenants` loses its connections too.

The client sees WebSocket close 1008 `credentials revoked or changed: authenticate again`; both clients then reconnect,
and a revoked credential's reconnect is refused (401) like any new connection. A reply to an RPC that was in flight is
not delivered after the revocation. The log says which caller lost how many connections and why (`wsapi closed N open
connection(s) of api:<tenant>/<user>[role] (API token <id>): its API token <id> was revoked`), and
`wsapi.auth.revoked.connections` counts them. A credential file that is refused (unreadable, not owner-only, malformed)
lets no per-user credential in, so it also closes their open connections until it is fixed (fail closed): change it
with `CredentialTool`, which replaces it atomically. Authentication used to happen only at the upgrade, so a leaked token
on a long-lived connection (GaiaKeep's tooling holds its connections open) kept working after it was revoked.

**API requests.** A caller has at most `wsapi_rpc_max_inflight_user` (8) or `wsapi_rpc_max_inflight_admin` (32) RPCs in
flight across its connections; more are answered at once with status `429` (busy), and a full pool with `503`; an RPC
never runs on a network thread. A `user` asks for at most `wsapi_rpc_max_timeout_user_ms` (120 s) per RPC, an admin up
to an hour. A `user` may address only its agent's own region (an agent joins only a region controller of its own
tenant, so that is the one region known to be the user's tenant; the controller does not tell wsapi the tenant of
other regions): a message to another region, or to a global controller in another region, is refused (403).

**The log stream.** Only an admin (or the service key) may set log levels or log routing; a `user`'s configuration lines
are refused, and its log stream never carries lines below `Warn` (Info, Debug and Trace can carry message contents).
A configuration line's RPCs (`setlogdp`, `setloglevel`) run on a small bounded pool, never a network thread, each
waiting at most 10 s for the named agent; one configuration at a time per stream, at most 2 per admin across its
streams (a fair share of the pool), and a line sent meanwhile, or when the pool is full, is answered
`busy: ...; retry shortly`. A line naming an agent that does not answer used to hold its event loop, and every client
on it, for 30 s. When a log stream closes, log streaming is turned off again on every agent it turned it on at, also
when the configuration is still in flight (it stops at its next step and then undoes) or was refused; it used to stay
on, mirroring to nobody, until that agent restarted. These undos (each RPC waits at most 10 s) run on their own bounded
pool (4 threads, 4,096 closed streams waiting), never on the JVM's common ForkJoin pool, which a full configuration pool
used to fall back to; if even that queue is full, an ERROR names the agents left streaming.
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
| `wsapi_auth_max_failures` | 10 | failures per bucket (IPv4 address, IPv6 prefix) per window before a lockout; also the budget of new addresses in a bucket a user is known in |
| `wsapi_auth_max_user_failures` | 50 | failures per user name per window from sources not known for that user |
| `wsapi_auth_ipv6_prefix_bits` | 64 | the IPv6 prefix one bucket aggregates (32 to 128; 128 where a /64 is shared) |
| `wsapi_auth_known_good_days` | 30 | how long a successful login keeps its address and bucket known good (kept across restarts) |
| `wsapi_service_key_limits_per_source` | true | count the service key's connection and RPC limits per source host |
| `wsapi_auth_share_ipv4_prefix_bits` | 24 | the IPv4 network one fair share of a user's unknown-source budget covers (16 to 32) |
| `wsapi_auth_delayed_check_seconds` | 30 | the first delay of a source beyond its fair share (doubling to `wsapi_auth_max_lockout_seconds`) |
| `wsapi_auth_window_seconds` | 60 | the counting window |
| `wsapi_auth_lockout_seconds` / `wsapi_auth_max_lockout_seconds` | 60 / 900 | first lockout, and the cap of the doubling |
| `wsapi_auth_cache_seconds` | 300 | how long a verified password is remembered; 0 turns it off |
| `wsapi_revocation_check_ms` | 1000 | how often every open connection's credential is checked again (250 to 5000) |
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
| `wsapi_max_inflight_bytes_per_user` | 268435456 (256 MiB) | inbound message bytes one caller's connections may hold together (at least the largest message cap) |
| `wsapi_max_inflight_bytes` | half of Netty's direct memory, 256 MiB to 2 GiB | inbound message bytes all connections may hold together |

A message over its endpoint's cap, in one frame or in fragments, closes the connection with WebSocket status 1009
(message too big), its reason naming the setting. Every endpoint used to accept 1 GiB, buffered whole per connection.

The inbound message bytes wsapi holds while messages arrive are bounded too: per caller, all its connections together
(`wsapi_max_inflight_bytes_per_user`, 256 MiB, raised with a WARNING to the largest message cap if set below it), and
for everyone together (`wsapi_max_inflight_bytes`). Bytes are counted as they arrive and given back once a whole message
has been handled. A connection whose next bytes would exceed either bound is closed with WebSocket status 1013 (try
again later), its reason naming the setting; the caller's other connections are not affected. One user with 30
connections each sending a 60 MiB frame used to hold 1.8 GB. `wsapi.inbound.inflight.bytes` shows what is held now and
`wsapi.inbound.budget.closed` counts the closed connections; the bounds are logged at start.

### The dataplane

- **Only callers of the agent's tenant** may open a dataplane stream: the dataplane wsapi reads and writes is that
  tenant's. Anyone else (an admin of a superuser tenant included) gets the activation reply `status_code=9`.
- **A `user` sees only its own streams and streams granted to it.** Its streams are named `u:<user>:...` (ident_key
  `stream_name`); `CredentialTool grant` adds others (an exact name, or a prefix ending in `*`). A raw subscription must
  anchor `stream_name = '<such a stream>'`, so a user cannot subscribe to another caller's transfers or a process's
  output (`seq_num='0'`, `type='output'`, ...). A user writes the types the executor writes (`output`, `error`,
  `event`) only on its own streams, so on a stream granted to it a user cannot pose as a process's stdout, stderr or
  exit event. Admins and the service key are not limited by ownership.
- **No stdin on the dataplane.** The executor takes a process's stdin only from its `write_stdin` action (a MsgEvent,
  accepted from the caller that started the runner, an admin or infra; the clients' `executor.write_stdin`). A
  handshake that would write stdin (`io_type_key=type` with `input_id` `input` or `input-*`) is refused for every
  caller, admins included, with that reason (`status_code=9`). It used to be accepted from admins, and the hardened
  executor would silently drop what it carried.
- **Every routing property is checked.** A handshake's `io_type_key` may not name its `ident_key`'s property (for
  everyone; the second value replaced the stream the handshake was checked for, so a user wrote into another user's
  stream), and a `user` may set only `type` beside its own `stream_name`, so no other subscriber's selector
  (`gk_stream='...'`) can be fed by a user's messages. A selector handshake only receives.
- **Envelope frames: who wrote it.** A handshake with `"envelope": "true"` gets every message with its verified writer.
  Text, map and streamfile terminal messages arrive as JSON text frames
  `{"kind": "text"|"map"|"transfer_end", "writer", "writer_roles", "writer_tenant", "properties": {...},
  "text": "..." | "map": {...}}`; data messages as binary frames: a 4-byte big-endian header length, the UTF-8 JSON
  header (`"kind": "bytes"`, the writer, the properties), then the payload as published. The writer is the API user
  wsapi published for (`api:<tenant>/<user>`, which the controller keeps only from wsapi and only for its tenant's
  users), else the bundle or controller its controller stamped (`cresco_caller`, e.g. the executor plugin); null when
  there is no stamp. So a client tells a process's real `execution_log` (a map, written by the executor) from a user's
  text that looks like one. Without `envelope` frames are as before, except that a map or streamfile terminal frame
  (an executor event, a transfer's end) also carries `cresco_writer` and `cresco_writer_roles` keys naming its writer;
  existing clients ignore them; the event's numbers are passed through as written (a long `42` stays `42`, a long
  above 2^53 stays exact; plain and envelope map frames used to turn every number into a double, `42.0`). A user cannot
  forge them: a client's text frame that is a JSON object with a top-level `cresco_writer...` key (any case) closes the
  connection (1008). The frame is parsed to decide, as leniently as Gson reads JSON (which accepts all a strict parser
  does), so a key spelled with escapes (`"cresco\u005fwriter"`) or given twice counts, however a client's parser decodes
  it; a frame that may spell the key and is nested more than 1,024 levels deep is refused rather than parsed.
- **Reserved properties.** No client may name a property infrastructure writes as its `ident_key` or `io_type_key`:
  `cresco_*`, `transfer_*`, `seq_num`, `offset`, `total_bytes`, `sha384`, `start_byte`, `byte_length`, `dp_bytes`,
  `region_id`, `agent_id`, `plugin_id`, `pluginname`, `event`, `loglevel`, `logid`, `session_id`. Everything a client
  publishes carries its verified writer, `cresco_writer=api:<tenant>/<user>` and `cresco_writer_roles=<role>`.
- A refused handshake answers `status_code=9` with the reason; the next text frame is a new handshake, and the third
  refusal closes the connection.
- **wsapi subscribes with the client's selector, checked, and nothing else.** The controller refuses any dataplane
  selector that names a process's stdin (`'input'`, `'input-...`) from every bundle but the executor; that guard is the
  single enforcement point. wsapi used to append `AND (type IS NULL OR (type <> 'input' ...))` to every subscription,
  which the hardened controller refuses, so no API client could receive.
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
