package io.cresco.wsapi.tls;

import io.cresco.library.crypto.CryptoPolicy;
import io.cresco.library.security.OwnerOnlyFile;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The wsapi TLS identity: a PKCS12 keystore, its password, and the published certificate and fingerprints.
 *
 * <p><b>Managed keystore</b> (the default), in the plugin data directory:
 * <ul>
 *   <li>{@code ws.keystore}: PKCS12, mode 0600. A key made with the crypto policy's algorithms: EC P-384 signed
 *       {@code SHA384withECDSA} by default ({@code wsapi_tls_key_type=RSA}: RSA-3072 or more, {@code SHA384withRSA}).
 *       Subject {@code O=<region>, OU=<agent>, CN=<plugin id>} (clients read the endpoint identity from it); subject
 *       alternative names {@code localhost}, this host's names and addresses, and {@code wsapi_tls_san}.</li>
 *   <li>{@code ws.keystore.pass}: the keystore password, 32 random bytes, mode 0600, read only if owner-only
 *       ({@link OwnerOnlyFile}). {@code wsapi_keystore_password} (or {@code wsapi_keystore_password_file}) replaces
 *       it when it has at least 12 characters and is not the old default; a weaker one is never used to protect a
 *       keystore.</li>
 *   <li>{@code ws.crt.pem} and {@code ws.fingerprint}: the certificate and its SHA-256 and SHA-384 fingerprints, for
 *       clients to trust (CA file) or pin. Public; rewritten at every start.</li>
 * </ul>
 * An existing keystore keeps loading, and its certificate keeps being served, so clients that trust it keep working:
 * <ul>
 *   <li>one that only opens under the old default password {@code cresco} or a weak configured password, or that is
 *       JKS, is re-saved as PKCS12 under a random password ({@code ws.keystore.pass}); the certificate is unchanged;</li>
 *   <li>a certificate below the crypto policy (an older wsapi's RSA-2048 / {@code SHA256withRSA}) is logged as a
 *       WARNING at every start; {@code wsapi_tls_replace_noncompliant=true} replaces it with a policy-strength one
 *       (clients must then trust the new fingerprint);</li>
 *   <li>an expired certificate is replaced (a verifying client could not use it anyway).</li>
 * </ul>
 * A replacement logs a WARNING naming the old and new fingerprints. A keystore that none of its passwords opens is
 * refused (it may have been tampered with): wsapi does not start until an operator deletes it and its password file.
 *
 * <p><b>Operator keystore</b>: {@code wsapi_keystore_file} names a PKCS12 file (for a CA-issued certificate) and
 * {@code wsapi_keystore_password} its password. It is never modified or replaced; a store that does not meet the policy
 * is refused.
 */
public final class WsapiKeystore {

    public static final String KEYSTORE = "ws.keystore";
    public static final String PASSWORD_FILE = "ws.keystore.pass";
    public static final String CERT_PEM = "ws.crt.pem";
    public static final String FINGERPRINT_FILE = "ws.fingerprint";
    public static final String ALIAS = "wsapi";
    /** The password older wsapi builds used for every keystore: opens an existing one, never protects one. */
    static final String LEGACY_DEFAULT_PASSWORD = "cresco";
    static final int MIN_CONFIGURED_PASSWORD_CHARS = 12;
    static final int MIN_RSA_BITS = 3072;

    /** Where problems are reported. */
    public interface Log {
        void info(String msg);
        void warn(String msg);
    }

    /** Startup refused: wsapi must not serve with this keystore. The message says what an operator should do. */
    public static final class Refused extends RuntimeException {
        public Refused(String msg) { super(msg); }
        public Refused(String msg, Throwable cause) { super(msg, cause); }
    }

    /** Inputs. */
    public static final class Settings {
        Path dataDir;
        Path operatorKeystore;
        char[] configuredPassword;
        String keyType = "EC";
        int rsaBits = MIN_RSA_BITS;
        int validityDays = 730;
        List<String> extraSans = new ArrayList<>();
        String region = "unknown", agent = "unknown", plugin = "unknown";
        boolean hostSans = true;
        boolean replaceNonCompliant = false;

        public Settings dataDir(Path p) { dataDir = p; return this; }
        public Settings operatorKeystore(Path p) { operatorKeystore = p; return this; }
        public Settings configuredPassword(char[] p) { configuredPassword = p; return this; }
        public Settings keyType(String t) { if (t != null) keyType = t.trim().toUpperCase(Locale.ROOT); return this; }
        public Settings rsaBits(int b) { rsaBits = b; return this; }
        public Settings validityDays(int d) { validityDays = d; return this; }
        public Settings extraSans(List<String> s) { extraSans = s == null ? new ArrayList<>() : s; return this; }
        public Settings identity(String region, String agent, String plugin) { this.region = region; this.agent = agent; this.plugin = plugin; return this; }
        /** Tests turn off this host's names and addresses in the SAN list. */
        public Settings hostSans(boolean b) { hostSans = b; return this; }
        /** Replace an existing certificate that is below the crypto policy ({@code wsapi_tls_replace_noncompliant}). */
        public Settings replaceNonCompliant(boolean b) { replaceNonCompliant = b; return this; }
    }

    /** The loaded identity. */
    public static final class Material {
        public final KeyStore keyStore;
        public final char[] password;
        public final X509Certificate certificate;
        public final String sha256;
        public final String sha384;
        public final Path source;
        public final boolean generated;

        Material(KeyStore ks, char[] pw, X509Certificate cert, Path source, boolean generated) {
            this.keyStore = ks;
            this.password = pw;
            this.certificate = cert;
            this.sha256 = fingerprint(cert, "SHA-256");
            this.sha384 = fingerprint(cert, "SHA-384");
            this.source = source;
            this.generated = generated;
        }
    }

    private WsapiKeystore() {}

    /** Load (or make) the wsapi TLS identity and publish its certificate and fingerprints. */
    public static Material loadOrCreate(Settings s, Log log) {
        if (s.dataDir == null) throw new Refused("no wsapi data directory");
        try {
            Files.createDirectories(s.dataDir);
        } catch (IOException e) {
            throw new Refused("cannot create the wsapi data directory " + s.dataDir + ": " + e.getMessage(), e);
        }
        Material m = s.operatorKeystore != null ? operator(s) : managed(s, log);
        publish(s.dataDir, m, log);
        return m;
    }

    // ------------------------------------------------------------------ operator keystore

    private static Material operator(Settings s) {
        Path ks = s.operatorKeystore;
        if (s.configuredPassword == null || s.configuredPassword.length == 0) {
            throw new Refused("wsapi_keystore_file is set but wsapi_keystore_password (or wsapi_keystore_password_file) is not");
        }
        if (!Files.isRegularFile(ks)) throw new Refused("wsapi_keystore_file " + ks + " does not exist");
        KeyStore store;
        try {
            store = open(ks, s.configuredPassword);
        } catch (Exception e) {
            throw new Refused("wsapi_keystore_file " + ks + " does not open as PKCS12 with wsapi_keystore_password (JKS is not "
                    + "accepted; convert it with keytool -importkeystore -deststoretype PKCS12): " + e.getMessage(), e);
        }
        ServedIdentity e = servedIdentity(store, s.configuredPassword, ks);
        String why = policyProblem(e.cert);
        if (why != null) throw new Refused("wsapi_keystore_file " + ks + ": " + why);
        if (expired(e.cert)) throw new Refused("wsapi_keystore_file " + ks + ": the certificate expired on " + e.cert.getNotAfter().toInstant());
        return new Material(e.store, s.configuredPassword.clone(), e.cert, ks, false);
    }

    // ------------------------------------------------------------------ managed keystore

    private static boolean strong(char[] pw) {
        return pw != null && pw.length >= MIN_CONFIGURED_PASSWORD_CHARS && !new String(pw).equals(LEGACY_DEFAULT_PASSWORD);
    }

    /** An existing keystore, opened. */
    private static final class Opened {
        final KeyStore store;
        final char[] password;
        final String source;
        final boolean jks;

        Opened(KeyStore store, char[] password, String source, boolean jks) {
            this.store = store;
            this.password = password;
            this.source = source;
            this.jks = jks;
        }
    }

    private static Material managed(Settings s, Log log) {
        Path ks = s.dataDir.resolve(KEYSTORE);
        Path passFile = s.dataDir.resolve(PASSWORD_FILE);
        char[] configured = (s.configuredPassword != null && s.configuredPassword.length > 0) ? s.configuredPassword : null;
        boolean configuredStrong = strong(configured);
        char[] filePassword = null;
        if (Files.exists(passFile)) {
            try {
                filePassword = OwnerOnlyFile.read(passFile, PASSWORD_FILE).toCharArray();
            } catch (OwnerOnlyFile.UnsafeFileException e) {
                throw new Refused("the wsapi keystore password file is unsafe: " + e.getMessage(), e);
            }
            if (filePassword.length == 0) throw new Refused(passFile + " is empty; delete it and " + ks + " to make a new keystore");
        }

        if (Files.exists(ks)) {
            Opened o = openExisting(ks, configured, filePassword, passFile);
            ServedIdentity id = servedIdentity(o.store, o.password, ks);
            X509Certificate cert = id.cert;
            String policy = policyProblem(cert);
            String replaceWhy = expired(cert) ? "its certificate expired on " + cert.getNotAfter().toInstant()
                    : (policy != null && s.replaceNonCompliant) ? policy + " (wsapi_tls_replace_noncompliant=true)" : null;
            if (replaceWhy == null) {
                char[] password = o.password;
                boolean weak = !strong(o.password);
                if (o.jks || weak) {
                    // keep the certificate (clients that trust it keep working); protect it properly from now on
                    password = configuredStrong ? configured : randomPassword();
                    rewrap(ks, passFile, id, o.password, password, !configuredStrong);
                    log.warn("wsapi TLS keystore " + ks + " was " + (o.jks ? "JKS" : "") + (o.jks && weak ? " and " : "")
                            + (weak ? "protected by " + o.source : "") + "; re-saved as PKCS12 under "
                            + (configuredStrong ? "wsapi_keystore_password" : "a random password in " + passFile)
                            + ". Its certificate is unchanged (" + fingerprint(cert, "SHA-384") + ")");
                }
                if (configured != null && !configuredStrong) {
                    log.warn("wsapi_keystore_password is too weak to protect the keystore and is not used; remove it from the configuration");
                }
                if (policy != null) {
                    log.warn("wsapi TLS certificate " + fingerprint(cert, "SHA-384") + " is below the crypto policy: " + policy
                            + ". It is kept so clients that trust it keep working. To replace it with a policy-strength certificate set "
                            + "wsapi_tls_replace_noncompliant=true and restart, then give clients the new fingerprint from " + FINGERPRINT_FILE);
                }
                long days = Duration.between(Instant.now(), cert.getNotAfter().toInstant()).toDays();
                if (days < 30) log.warn("wsapi TLS certificate expires in " + days + " days (" + cert.getNotAfter().toInstant()
                        + "); delete " + ks + " to make a new one, then re-pin clients");
                return new Material(id.store, password, cert, ks, false);
            }
            log.warn("wsapi TLS keystore " + ks + " is replaced: " + replaceWhy + " (old certificate " + fingerprint(cert, "SHA-384") + ")");
            try {
                Files.delete(ks);
            } catch (IOException e) {
                throw new Refused("cannot remove the wsapi keystore " + ks + " to replace it: " + e.getMessage(), e);
            }
        }

        char[] password;
        if (configuredStrong) {
            password = configured;
        } else {
            if (configured != null) {
                log.warn("wsapi_keystore_password is too weak (fewer than " + MIN_CONFIGURED_PASSWORD_CHARS
                        + " characters, or the old default) to protect a keystore; using a random password in " + passFile);
            }
            password = strong(filePassword) ? filePassword : randomPassword();
            if (password != filePassword) writeOwnerOnly(passFile, new String(password).getBytes(StandardCharsets.UTF_8));
        }
        X509Certificate cert;
        KeyStore store;
        try {
            KeyPair kp = generateKeyPair(s);
            cert = selfSigned(kp, s);
            store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry(ALIAS, kp.getPrivate(), password, new Certificate[]{cert});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            store.store(bos, password);
            writeOwnerOnly(ks, bos.toByteArray());
        } catch (Refused r) {
            throw r;
        } catch (Exception e) {
            throw new Refused("could not make the wsapi TLS keystore " + ks + ": " + e, e);
        }
        log.info("wsapi TLS keystore created: " + ks + " (" + describeKey(cert.getPublicKey()) + ", " + cert.getSigAlgName() + ")");
        return new Material(store, password, cert, ks, true);
    }

    /**
     * Open an existing managed keystore with the first password that works: the configured one, the password file, then
     * the old default (an older wsapi's store, or one whose re-save was interrupted). PKCS12 or (older wsapi) JKS.
     */
    private static Opened openExisting(Path ks, char[] configured, char[] filePassword, Path passFile) {
        List<Object[]> candidates = new ArrayList<>();
        if (configured != null) candidates.add(new Object[]{configured, "wsapi_keystore_password"});
        if (filePassword != null) candidates.add(new Object[]{filePassword, passFile.toString()});
        candidates.add(new Object[]{LEGACY_DEFAULT_PASSWORD.toCharArray(), "the old default password"});
        boolean jks;
        try (InputStream in = Files.newInputStream(ks)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            jks = n == 4 && (head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xED && (head[2] & 0xff) == 0xFE && (head[3] & 0xff) == 0xED;
            if (!jks && (n < 1 || head[0] != 0x30)) throw new Refused("the wsapi keystore " + ks + " is neither PKCS12 nor JKS");
        } catch (IOException e) {
            throw new Refused("cannot read the wsapi keystore " + ks + ": " + e.getMessage(), e);
        }
        List<String> tried = new ArrayList<>();
        for (Object[] c : candidates) {
            char[] pw = (char[]) c[0];
            tried.add((String) c[1]);
            try (InputStream in = Files.newInputStream(ks)) {
                KeyStore store = KeyStore.getInstance(jks ? "JKS" : "PKCS12");
                store.load(in, pw);
                return new Opened(store, pw, (String) c[1], jks);
            } catch (Exception wrong) {
                // the next candidate
            }
        }
        throw new Refused("the wsapi keystore " + ks + " does not open with " + String.join(", ", tried)
                + "; it may have been replaced. Delete " + ks + " and " + passFile
                + " to make a new one (clients must then trust the new fingerprint)");
    }

    /** Re-save a served identity as PKCS12 under {@code newPassword}; when {@code toFile}, that password goes to the password file. */
    private static void rewrap(Path ks, Path passFile, ServedIdentity id, char[] oldPassword, char[] newPassword, boolean toFile) {
        try {
            Key k = id.store.getKey(ALIAS, oldPassword);
            Certificate[] chain = id.store.getCertificateChain(ALIAS);
            KeyStore fresh = KeyStore.getInstance("PKCS12");
            fresh.load(null, null);
            fresh.setKeyEntry(ALIAS, k, newPassword, chain);
            id.store.deleteEntry(ALIAS);
            id.store.setKeyEntry(ALIAS, k, newPassword, chain);       // the served copy, under the new password
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            fresh.store(bos, newPassword);
            // the store first under a temporary name, then the password, then the store into place: if this stops
            // half way the old store still opens (with the old default) and is re-saved again at the next start
            Path tmp = ks.resolveSibling(ks.getFileName() + ".new");
            writeOwnerOnly(tmp, bos.toByteArray());
            if (toFile) writeOwnerOnly(passFile, new String(newPassword).getBytes(StandardCharsets.UTF_8));
            move(tmp, ks);
        } catch (Refused r) {
            throw r;
        } catch (Exception e) {
            throw new Refused("could not re-save the wsapi keystore " + ks + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ policy

    /** Why this certificate does not meet the crypto policy, or null when it does. */
    static String policyProblem(X509Certificate cert) {
        PublicKey k = cert.getPublicKey();
        CryptoPolicy policy = CryptoPolicy.effective();
        if (k instanceof RSAPublicKey) {
            int bits = ((RSAPublicKey) k).getModulus().bitLength();
            if (bits < MIN_RSA_BITS) return "its key is RSA-" + bits + "; at least RSA-" + MIN_RSA_BITS + " is required";
        } else if (k instanceof ECPublicKey) {
            int bits = ((ECPublicKey) k).getParams().getCurve().getField().getFieldSize();
            int min = CryptoPolicy.LEVEL_CNSA1.equals(policy.level()) ? 384 : 256;
            if (bits < min) return "its key is EC-" + bits + "; the crypto policy (" + policy.level() + ") requires at least EC-" + min;
        } else {
            return "its key type " + k.getAlgorithm() + " is not RSA or EC";
        }
        String sig = cert.getSigAlgName();
        String canonical = CryptoPolicy.approvedName(CryptoPolicy.Family.SIGNATURE, sig);
        if (canonical == null || !policy.allows(CryptoPolicy.Family.SIGNATURE, canonical)) {
            return "its signature algorithm " + sig + " is not allowed by the crypto policy (" + policy.level() + ": "
                    + policy.allowed(CryptoPolicy.Family.SIGNATURE) + ")";
        }
        return null;
    }

    static boolean expired(X509Certificate cert) {
        return cert.getNotAfter().toInstant().isBefore(Instant.now());
    }

    // ------------------------------------------------------------------ generation

    private static KeyPair generateKeyPair(Settings s) throws Exception {
        if ("RSA".equals(s.keyType)) {
            if (s.rsaBits < MIN_RSA_BITS) throw new Refused("wsapi_tls_rsa_bits " + s.rsaBits + " is below RSA-" + MIN_RSA_BITS);
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(s.rsaBits, new SecureRandom());
            return g.generateKeyPair();
        }
        if (!"EC".equals(s.keyType)) throw new Refused("wsapi_tls_key_type must be EC or RSA, not " + s.keyType);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"), new SecureRandom());
        return g.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, Settings s) throws Exception {
        String sigAlg = CryptoPolicy.effective().signatureFor(kp.getPublic().getAlgorithm());
        Instant notBefore = Instant.now().minus(Duration.ofHours(1));
        int days = Math.max(1, Math.min(3650, s.validityDays));
        Instant notAfter = notBefore.plus(Duration.ofDays(days));
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.O, bounded(s.region))
                .addRDN(BCStyle.OU, bounded(s.agent))
                .addRDN(BCStyle.CN, bounded(s.plugin))
                .build();
        BigInteger serial = new BigInteger(159, new SecureRandom());
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(subject, serial, Date.from(notBefore), Date.from(notAfter),
                subject, kp.getPublic());
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        int usage = KeyUsage.digitalSignature | ("RSA".equals(kp.getPublic().getAlgorithm()) ? KeyUsage.keyEncipherment : 0);
        b.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
        List<GeneralName> names = new ArrayList<>();
        for (String n : sans(s)) {
            names.add(isIp(n) ? new GeneralName(GeneralName.iPAddress, n) : new GeneralName(GeneralName.dNSName, n));
        }
        if (!names.isEmpty()) {
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(new GeneralName[0])));
        }
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    /** localhost, 127.0.0.1, ::1, this host's names and interface addresses, then wsapi_tls_san. */
    static Set<String> sans(Settings s) {
        Set<String> out = new LinkedHashSet<>();
        out.add("localhost");
        out.add("127.0.0.1");
        out.add("::1");
        if (s.hostSans) {
            try {
                InetAddress local = InetAddress.getLocalHost();
                addName(out, local.getHostName());
                addName(out, local.getCanonicalHostName());
            } catch (Exception ignore) {
                // no resolvable host name
            }
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a.isLinkLocalAddress() || a.isLoopbackAddress()) continue;
                        String h = a.getHostAddress();
                        int pct = h.indexOf('%');
                        out.add(pct >= 0 ? h.substring(0, pct) : h);
                    }
                }
            } catch (Exception ignore) {
                // no interfaces visible
            }
        }
        for (String x : s.extraSans) addName(out, x);
        return out;
    }

    private static void addName(Set<String> out, String n) {
        if (n == null) return;
        String t = n.trim();
        if (t.isEmpty() || t.length() > 253) return;
        if (isIp(t) || t.matches("[A-Za-z0-9*]([A-Za-z0-9.-]*[A-Za-z0-9])?")) out.add(t);
    }

    static boolean isIp(String s) {
        return s.matches("[0-9.]+") && s.chars().filter(c -> c == '.').count() == 3 || (s.contains(":") && s.matches("[0-9A-Fa-f:.]+"));
    }

    private static String bounded(String v) {
        if (v == null || v.isEmpty()) return "unknown";
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    // ------------------------------------------------------------------ files

    private static KeyStore open(Path ks, char[] password) throws Exception {
        // The JDK's PKCS12 KeyStore also reads JKS/JCEKS files (keystore.type.compat), so check the format itself:
        // PKCS12 is a DER SEQUENCE (0x30); JKS starts FE ED FE ED and JCEKS CE CE CE CE.
        byte[] head = new byte[1];
        try (InputStream in = Files.newInputStream(ks)) {
            if (in.read(head) != 1 || head[0] != 0x30) throw new IOException("not a PKCS12 file (JKS and JCEKS are not accepted)");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, password);
        }
        return store;
    }

    /** A store's one private key entry ({@code wsapi} when there are several), as a store holding only that entry. */
    static final class ServedIdentity {
        final KeyStore store;
        final X509Certificate cert;

        ServedIdentity(X509Certificate cert, KeyStore served) {
            this.store = served;
            this.cert = cert;
        }
    }

    private static ServedIdentity servedIdentity(KeyStore store, char[] password, Path where) {
        try {
            List<String> keys = new ArrayList<>();
            for (Enumeration<String> a = store.aliases(); a.hasMoreElements(); ) {
                String n = a.nextElement();
                if (store.isKeyEntry(n)) keys.add(n);
            }
            String alias = keys.contains(ALIAS) ? ALIAS : (keys.size() == 1 ? keys.get(0) : null);
            if (alias == null) {
                throw new Refused(where + (keys.isEmpty() ? " holds no private key"
                        : " holds " + keys.size() + " private keys and none is named " + ALIAS + "; name the one wsapi serves " + ALIAS));
            }
            Key k = store.getKey(alias, password);
            if (!(k instanceof PrivateKey)) throw new Refused(where + ": entry " + alias + " is not a private key");
            Certificate c = store.getCertificate(alias);
            if (!(c instanceof X509Certificate)) throw new Refused(where + ": entry " + alias + " has no X.509 certificate");
            // serve exactly this entry: a key manager over a store with several keys may pick another one
            KeyStore one = KeyStore.getInstance("PKCS12");
            one.load(null, null);
            one.setKeyEntry(ALIAS, k, password, store.getCertificateChain(alias));
            return new ServedIdentity((X509Certificate) c, one);
        } catch (Refused r) {
            throw r;
        } catch (Exception e) {
            throw new Refused(where + ": cannot read its key entry: " + e.getMessage(), e);
        }
    }

    private static char[] randomPassword() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).toCharArray();
    }

    /** Write a file atomically with mode 0600 (a 0600 temp file in the same directory, renamed over the target). */
    static void writeOwnerOnly(Path file, byte[] data) {
        try {
            Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    out.write(data);
                }
                move(tmp, file);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (UnsupportedOperationException noPosix) {
            throw new Refused("the filesystem of " + file + " has no POSIX permissions, so it cannot be kept owner-only");
        } catch (IOException e) {
            throw new Refused("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static void writePublic(Path file, String text) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignore) {
                // not POSIX: the default permissions stay
            }
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Write ws.crt.pem and ws.fingerprint, and log the fingerprints. */
    private static void publish(Path dir, Material m, Log log) {
        try {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(m.certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
            writePublic(dir.resolve(CERT_PEM), pem);
            writePublic(dir.resolve(FINGERPRINT_FILE), m.sha384 + "\n" + m.sha256 + "\n");
        } catch (Exception e) {
            log.warn("could not publish the wsapi certificate in " + dir + ": " + e.getMessage());
        }
        log.info("wsapi TLS certificate fingerprint " + m.sha384 + " (" + m.sha256 + "); clients pin it with "
                + "fingerprint=... or trust " + dir.resolve(CERT_PEM) + " as a CA file (fingerprints in " + dir.resolve(FINGERPRINT_FILE) + ")");
    }

    /** {@code sha256:AB:CD:...} / {@code sha384:...}. */
    public static String fingerprint(X509Certificate cert, String alg) {
        try {
            byte[] d = MessageDigest.getInstance(alg).digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(alg.replace("-", "").toLowerCase(Locale.ROOT)).append(':');
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(alg + " fingerprint failed", e);
        }
    }

    private static String describeKey(PublicKey k) {
        if (k instanceof RSAPublicKey) return "RSA-" + ((RSAPublicKey) k).getModulus().bitLength();
        if (k instanceof ECPublicKey) return "EC P-" + ((ECPublicKey) k).getParams().getCurve().getField().getFieldSize();
        return k.getAlgorithm();
    }
}
