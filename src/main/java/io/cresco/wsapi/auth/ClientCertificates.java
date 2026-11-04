package io.cresco.wsapi.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.cert.CRL;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Client certificates, the only API credential of the production profile ({@code security_profile=production}): the
 * client CAs ({@code wsapi_client_ca}, a PEM bundle) a certificate must chain to (TLS checks the chain and its validity
 * dates), the mapping from a certificate to a Cresco API caller, and revocation.
 *
 * <p><b>The mapping</b>: the certificate's subject alternative names, URIs:
 * <ul>
 *   <li>exactly one {@code cresco:api:<tenant>/<user>}: the caller ({@code api:<tenant>/<user>});</li>
 *   <li>exactly one {@code cresco:role:admin} or {@code cresco:role:user};</li>
 *   <li>any number of {@code cresco:grant:<stream or prefix*>}: dataplane streams granted besides the user's own.</li>
 * </ul>
 * A certificate without them (or with two callers or two roles) is refused. The CA that issues them decides who is who.
 *
 * <p><b>Revocation</b>, re-read within a second of a change and applied to open connections too (the gate's revocation
 * check closes them with 1008): a deny list ({@code wsapi_client_deny_file}, one SHA-256 certificate fingerprint per
 * line, as {@code ws.fingerprint} writes them: hex, colons and a {@code sha256:} prefix optional, {@code #} comments)
 * and a CRL file ({@code wsapi_client_crl}, PEM or DER, each CRL signed by one of the client CAs). Either file must not
 * be writable by anyone but its owner; one that cannot be read, does not parse, or whose CRL does not verify, accepts no
 * client certificate at all (fail closed) until it is fixed. An expired certificate is closed when it expires.
 */
public final class ClientCertificates {

    /** A certificate's caller: tenant, user, role and grants. */
    public static final class Mapping {
        public final String tenant, user, role;
        public final List<String> grants;

        Mapping(String tenant, String user, String role, List<String> grants) {
            this.tenant = tenant;
            this.user = user;
            this.role = role;
            this.grants = List.copyOf(grants);
        }
    }

    /** A setting that cannot be used; the message names it. */
    public static final class Invalid extends IllegalArgumentException {
        Invalid(String msg) { super(msg); }
    }

    static final String API = "cresco:api:", ROLE = "cresco:role:", GRANT = "cresco:grant:";
    private static final int SAN_URI = 6;
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private final List<X509Certificate> cas;
    private final Path denyFile, crlFile;
    private final LongSupplier clock;
    private volatile Revocation current;
    private volatile long lastCheck;

    /** What revokes certificates now: the deny list, the CRLs, or why neither can be trusted (then nothing is accepted). */
    private static final class Revocation {
        final Object stamp;
        final Set<String> denied;
        final List<X509CRL> crls;
        final String problem;

        Revocation(Object stamp, Set<String> denied, List<X509CRL> crls, String problem) {
            this.stamp = stamp;
            this.denied = denied;
            this.crls = crls;
            this.problem = problem;
        }
    }

    /**
     * @param caBundle the client CAs (PEM); required
     * @param denyFile the fingerprint deny list, or null
     * @param crlFile  the CRL file, or null
     * @throws Invalid the CA bundle is missing, unreadable or holds no certificate
     */
    public ClientCertificates(Path caBundle, Path denyFile, Path crlFile, LongSupplier clock) {
        this.cas = List.copyOf(loadCas(caBundle));
        this.denyFile = denyFile;
        this.crlFile = crlFile;
        this.clock = clock;
        this.lastCheck = clock.getAsLong();
        this.current = load();
    }

    static List<X509Certificate> loadCas(Path bundle) {
        if (bundle == null) throw new Invalid("wsapi_client_ca is not set: production needs the client CA bundle (PEM)");
        if (!Files.isRegularFile(bundle)) throw new Invalid("wsapi_client_ca " + bundle + " does not exist");
        try (InputStream in = Files.newInputStream(bundle)) {
            List<X509Certificate> out = new ArrayList<>();
            for (java.security.cert.Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) throw new Invalid("wsapi_client_ca " + bundle + " holds no certificate");
            return out;
        } catch (IOException | CertificateException e) {
            throw new Invalid("wsapi_client_ca " + bundle + " is not a PEM certificate bundle: " + e.getMessage());
        }
    }

    /** The client CAs, for the TLS trust manager. */
    public X509Certificate[] cas() { return cas.toArray(new X509Certificate[0]); }

    /** Why revocation cannot be checked now (an unreadable or invalid deny list or CRL), or null. */
    public String problem() { return revocation().problem; }

    /** The SHA-256 fingerprint of a certificate (lowercase hex). */
    public static String fingerprint(X509Certificate c) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(c.getEncoded())) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The caller a certificate names, or throws {@link Invalid} saying why it names none. */
    public static Mapping map(X509Certificate c) {
        List<String> uris = new ArrayList<>();
        try {
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            if (sans != null) for (List<?> san : sans) if (san.size() >= 2 && Objects.equals(san.get(0), SAN_URI)) uris.add(String.valueOf(san.get(1)));
        } catch (CertificateException e) {
            throw new Invalid("its subject alternative names cannot be read");
        }
        String api = null, role = null;
        List<String> grants = new ArrayList<>();
        for (String u : uris) {
            if (u.startsWith(API)) {
                if (api != null) throw new Invalid("it names two callers (" + API + "...)");
                api = u.substring(API.length());
            } else if (u.startsWith(ROLE)) {
                if (role != null) throw new Invalid("it names two roles (" + ROLE + "...)");
                role = u.substring(ROLE.length());
            } else if (u.startsWith(GRANT)) {
                String g = u.substring(GRANT.length());
                if (!CredentialStore.GRANT.matcher(g).matches()) throw new Invalid("its grant " + g + " is not a stream name or prefix*");
                grants.add(g);
            }
        }
        if (api == null) throw new Invalid("it names no Cresco API caller (a subject alternative name URI " + API + "<tenant>/<user>)");
        int slash = api.indexOf('/');
        String tenant = slash < 0 ? "" : api.substring(0, slash), user = slash < 0 ? "" : api.substring(slash + 1);
        if (!CredentialStore.TENANT.matcher(tenant).matches() || !CredentialStore.USER.matcher(user).matches()
                || CredentialStore.SERVICE_KEY_USER.equals(user)) {
            throw new Invalid("its caller " + API + api + " is not <tenant>/<user> (Cresco tenant and API user names)");
        }
        if (!CredentialStore.ROLE_ADMIN.equals(role) && !CredentialStore.ROLE_USER.equals(role)) {
            throw new Invalid("it names no role (a subject alternative name URI " + ROLE + "admin or " + ROLE + "user)");
        }
        return new Mapping(tenant, user, role, grants);
    }

    /** Why this certificate is not accepted now (revoked, expired, or revocation cannot be checked), or null. */
    public String revoked(X509Certificate c) {
        Revocation r = revocation();
        if (r.problem != null) return "client certificates cannot be checked for revocation: " + r.problem;
        String fp = fingerprint(c);
        if (r.denied.contains(fp)) return "its client certificate sha256:" + fp + " is on the deny list (wsapi_client_deny_file)";
        for (X509CRL crl : r.crls) {
            if (crl.getIssuerX500Principal().equals(c.getIssuerX500Principal()) && crl.isRevoked(c)) {
                return "its client certificate (serial " + c.getSerialNumber().toString(16) + ") is revoked by the CRL (wsapi_client_crl)";
            }
        }
        if (c.getNotAfter().getTime() <= clock.getAsLong()) return "its client certificate expired at " + c.getNotAfter().toInstant();
        return null;
    }

    private Revocation revocation() {
        long now = clock.getAsLong();
        if (now - lastCheck >= 1000L || now < lastCheck) {
            synchronized (this) {
                if (now - lastCheck >= 1000L || now < lastCheck) {
                    lastCheck = now;
                    if (!Objects.equals(stamp(), current.stamp)) current = load();
                }
            }
        }
        return current;
    }

    private Object stamp() {
        return stampOf(denyFile) + "|" + stampOf(crlFile);
    }

    private static String stampOf(Path p) {
        if (p == null) return "-";
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            String access;
            try {
                PosixFileAttributes x = Files.readAttributes(p, PosixFileAttributes.class);
                access = x.owner().getName() + ":" + PosixFilePermissions.toString(x.permissions());
            } catch (UnsupportedOperationException noPosix) {
                access = "no-posix";
            }
            return a.lastModifiedTime().toMillis() + "/" + a.size() + "/" + a.fileKey() + "/" + access;
        } catch (IOException e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private Revocation load() {
        Object stamp = stamp();
        try {
            Set<String> denied = denyFile == null ? Set.of() : readDenyList(denyFile);
            List<X509CRL> crls = crlFile == null ? List.of() : readCrls(crlFile);
            return new Revocation(stamp, Collections.unmodifiableSet(denied), crls, null);
        } catch (Invalid | IOException e) {
            return new Revocation(stamp, Set.of(), List.of(), e.getMessage());
        }
    }

    /** A revocation file is trusted only when nobody but its owner can change it. */
    private static void checkWritable(Path p, String setting) throws IOException {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new Invalid(setting + " " + p + " is not a regular file");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new Invalid(setting + " " + p + " is writable by others (" + PosixFilePermissions.toString(perms) + ")");
            }
        } catch (UnsupportedOperationException noPosix) {
            throw new Invalid(setting + " " + p + ": this filesystem has no POSIX modes, so who may change it cannot be checked");
        }
    }

    static Set<String> readDenyList(Path p) throws IOException {
        checkWritable(p, "wsapi_client_deny_file");
        Set<String> out = new HashSet<>();
        int n = 0;
        for (String line : Files.readAllLines(p)) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String h = t.toLowerCase(Locale.ROOT);
            if (h.startsWith("sha256:")) h = h.substring(7);
            h = h.replace(":", "");
            if (!HEX64.matcher(h).matches()) throw new Invalid("wsapi_client_deny_file " + p + " line " + n + " is not a SHA-256 fingerprint");
            out.add(h);
        }
        return out;
    }

    private List<X509CRL> readCrls(Path p) throws IOException {
        checkWritable(p, "wsapi_client_crl");
        List<X509CRL> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(p)) {
            for (CRL c : CertificateFactory.getInstance("X.509").generateCRLs(in)) out.add((X509CRL) c);
        } catch (CertificateException | java.security.cert.CRLException e) {
            throw new Invalid("wsapi_client_crl " + p + " is not a CRL file (PEM or DER): " + e.getMessage());
        }
        for (X509CRL crl : out) {
            boolean verified = false;
            for (X509Certificate ca : cas) {
                if (!ca.getSubjectX500Principal().equals(crl.getIssuerX500Principal())) continue;
                try {
                    crl.verify(ca.getPublicKey());
                    verified = true;
                    break;
                } catch (Exception wrongKey) {
                    // another CA of the same name
                }
            }
            if (!verified) {
                throw new Invalid("wsapi_client_crl " + p + ": the CRL of " + crl.getIssuerX500Principal()
                        + " is not signed by a CA of wsapi_client_ca");
            }
        }
        return out;
    }

    /** CRLs whose next update has passed (still applied; publish fresh ones), for a warning. */
    public List<String> staleCrls() {
        List<String> out = new ArrayList<>();
        for (X509CRL crl : revocation().crls) {
            if (crl.getNextUpdate() != null && crl.getNextUpdate().getTime() < clock.getAsLong()) {
                out.add(crl.getIssuerX500Principal() + " (next update " + crl.getNextUpdate().toInstant() + ")");
            }
        }
        return out;
    }
}
