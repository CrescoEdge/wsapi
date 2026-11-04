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

An existing keystore keeps loading and its certificate keeps being served, so clients that trust it keep working. A
keystore from an older wsapi (JKS, or protected by the old default password `cresco` or a configured password under
12 characters) is re-saved once as PKCS12 under a random password in `ws.keystore.pass`, with the same certificate
(WARNING in the log). If the certificate is below the crypto policy (an older wsapi's RSA-2048 / `SHA256withRSA`),
every start logs a WARNING; set `wsapi_tls_replace_noncompliant=true` to replace it with a policy-strength certificate,
then give clients the new fingerprint. An expired certificate is replaced. Every replacement logs the old and new
fingerprints. A keystore that none of its passwords opens is refused, and wsapi does not start: delete `ws.keystore`
and `ws.keystore.pass` to make a new one, then re-pin clients.

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
  the agent's tenant. Set `wsapi_service_key_enabled=false` to turn it off once every client has its own credential.

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
java -cp wsapi.jar io.cresco.wsapi.auth.CredentialTool list   <ws.users>
```

Failed attempts are limited: 10 failures from one address within 60 s lock that address out for 60 s (HTTP 429 with
`Retry-After`, even with the right password), each repeat lockout doubling up to 15 min; 50 failures for one user name
within the window lock that user name the same way. Authentication runs off the network threads; a verified password
is remembered for `wsapi_auth_cache_seconds` (an HMAC under a per-process random key, never the password) so clients
that open several sockets do not pay PBKDF2 for each. Refusals are logged with the source address, the user name and
the reason; secrets never are.

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
| `wsapi_auth_max_failures` | 10 | failures per source address per window before a lockout |
| `wsapi_auth_max_user_failures` | 50 | failures per user name per window before a lockout |
| `wsapi_auth_window_seconds` | 60 | the counting window |
| `wsapi_auth_lockout_seconds` / `wsapi_auth_max_lockout_seconds` | 60 / 900 | first lockout, and the cap of the doubling |
| `wsapi_auth_cache_seconds` | 300 | how long a verified password is remembered; 0 turns it off |
| `wsapi_auth_threads` / `wsapi_auth_queue` | cores/2 (2-8) / 256 | authentication workers; a full queue answers 503 |

### Operator steps when upgrading

1. Start the upgraded agent. wsapi keeps serving its existing certificate (re-saving an older keystore under a random
   password, WARNING in the log) and writes `ws.crt.pem` and `ws.fingerprint` in its plugin data directory.
2. Give every client the fingerprint from `ws.fingerprint` (or the log line), or a copy of `ws.crt.pem`; with it the
   client verifies the certificate on every connection and sends nothing to a server that does not match:
   - Python (pycrescolib): `clientlib(host, port, service_key, fingerprint="sha384:...")`, or `ca_file="ws.crt.pem"`, or
     set `CRESCO_TLS_FINGERPRINT` / `CRESCO_TLS_CA_FILE` in the environment.
   - Java (clientlib): `CrescoClient.builder(host, port).serviceKey(key).pinnedFingerprint("sha384:...").build()`, or
     `.caFile(path)`, or `-Dcresco.client.tls.fingerprint=...` / `CRESCO_TLS_FINGERPRINT`.
3. Optional: set `wsapi_tls_replace_noncompliant=true` once to move an older certificate to the crypto policy's
   algorithms, and give clients the new fingerprint.
4. Optional: create per-user credentials with the `CredentialTool`, move clients to `user=` / `password=` (Python) or
   `.credentials(user, password)` (Java), then set `wsapi_service_key_enabled=false`.
