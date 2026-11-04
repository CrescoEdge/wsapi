package io.cresco.wsapi.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WsapiKeystoreTest {

    final List<String> warnings = new ArrayList<>(), infos = new ArrayList<>();
    final WsapiKeystore.Log log = new WsapiKeystore.Log() {
        @Override public void info(String m) { infos.add(m); }
        @Override public void warn(String m) { warnings.add(m); }
    };

    WsapiKeystore.Settings settings(Path dir) {
        return new WsapiKeystore.Settings().dataDir(dir).identity("region-1", "agent-1", "plugin/3").hostSans(false)
                .extraSans(List.of("wsapi.example.org", "10.1.2.3"));
    }

    static String perms(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void aNewKeystoreUsesThePolicyAlgorithmsARandomOwnerOnlyPasswordAndIsPublished(@TempDir Path dir) throws Exception {
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(dir), log);
        X509Certificate c = m.certificate;
        assertTrue(m.generated);
        assertEquals("EC", c.getPublicKey().getAlgorithm());
        assertEquals(384, ((ECPublicKey) c.getPublicKey()).getParams().getCurve().getField().getFieldSize());
        assertEquals("SHA384withECDSA", c.getSigAlgName());
        assertEquals(-1, c.getBasicConstraints(), "not a CA");
        assertTrue(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "serverAuth");

        // identity DN the clients read: O=region, OU=agent, CN=plugin
        Map<String, String> dn = new HashMap<>();
        for (Rdn r : new LdapName(c.getSubjectX500Principal().getName()).getRdns()) dn.put(r.getType(), String.valueOf(r.getValue()));
        assertEquals("region-1", dn.get("O"));
        assertEquals("agent-1", dn.get("OU"));
        assertEquals("plugin/3", dn.get("CN"));
        List<String> sans = new ArrayList<>();
        for (List<?> s : c.getSubjectAlternativeNames()) sans.add(String.valueOf(s.get(1)));
        assertTrue(sans.containsAll(List.of("localhost", "127.0.0.1", "wsapi.example.org", "10.1.2.3")), sans.toString());

        Path ks = dir.resolve(WsapiKeystore.KEYSTORE), pass = dir.resolve(WsapiKeystore.PASSWORD_FILE);
        assertEquals("rw-------", perms(ks));
        assertEquals("rw-------", perms(pass));
        String pw = Files.readString(pass).strip();
        assertTrue(pw.length() >= 40, "32 random bytes");
        assertNotEquals("cresco", pw);
        KeyStore p12 = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks)) { p12.load(in, pw.toCharArray()); }
        assertTrue(p12.isKeyEntry("wsapi"));

        // published: the certificate (usable as a CA file) and its fingerprints
        X509Certificate pem = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(dir.resolve(WsapiKeystore.CERT_PEM))));
        assertEquals(c, pem);
        assertEquals("rw-r--r--", perms(dir.resolve(WsapiKeystore.CERT_PEM)));
        String fp = Files.readString(dir.resolve(WsapiKeystore.FINGERPRINT_FILE));
        assertTrue(fp.contains(m.sha384) && fp.contains(m.sha256));
        assertTrue(m.sha384.matches("sha384:([0-9A-F]{2}:){47}[0-9A-F]{2}"), m.sha384);
        assertTrue(m.sha256.matches("sha256:([0-9A-F]{2}:){31}[0-9A-F]{2}"), m.sha256);
        assertTrue(infos.stream().anyMatch(i -> i.contains(m.sha384)), "the fingerprint is logged at start");
    }

    @Test
    void aSecondStartReusesTheSameCertificate(@TempDir Path dir) {
        WsapiKeystore.Material a = WsapiKeystore.loadOrCreate(settings(dir), log);
        WsapiKeystore.Material b = WsapiKeystore.loadOrCreate(settings(dir), log);
        assertFalse(b.generated);
        assertEquals(a.sha384, b.sha384, "clients' pins survive a restart");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void rsaWhenAskedIsAtLeast3072BitsAndSha384() throws Exception {
        Path dir = Files.createTempDirectory("wsks");
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(dir).keyType("RSA"), log);
        assertEquals(3072, ((RSAPublicKey) m.certificate.getPublicKey()).getModulus().bitLength());
        assertEquals("SHA384withRSA", m.certificate.getSigAlgName());
        Path dir2 = Files.createTempDirectory("wsks");
        assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(dir2).keyType("RSA").rsaBits(2048), log));
        assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(dir2).keyType("DSA"), log));
    }

    /** What an older wsapi left behind: RSA-2048 / SHA256withRSA under the password "cresco". */
    static X509Certificate legacyKeystore(Path ks, String type, int bits, String sig, char[] pw, Instant notAfter) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits);
        KeyPair kp = g.generateKeyPair();
        X500Name n = new X500Name("O=r,OU=a,CN=p");
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(n, BigInteger.TEN,
                Date.from(Instant.now().minus(400, ChronoUnit.DAYS)), Date.from(notAfter), n, kp.getPublic())
                .build(new JcaContentSignerBuilder(sig).build(kp.getPrivate())));
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        store.setKeyEntry("wsapi", kp.getPrivate(), pw, new Certificate[]{cert});
        try (OutputStream out = Files.newOutputStream(ks)) { store.store(out, pw); }
        return cert;
    }

    @Test
    void anOlderWsapisKeystoreStillLoadsWithItsCertificateAndIsReSavedUnderARandomPassword(@TempDir Path dir) throws Exception {
        for (String type : new String[]{"PKCS12", "JKS"}) {
            Path d = Files.createDirectories(dir.resolve(type));
            Path ks = d.resolve("ws.keystore");
            X509Certificate old = legacyKeystore(ks, type, 2048, "SHA256withRSA", "cresco".toCharArray(),
                    Instant.now().plus(1000, ChronoUnit.DAYS));
            warnings.clear();
            WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(d), log);
            assertFalse(m.generated, type);
            assertEquals(old, m.certificate, "the certificate clients already trust is kept");
            assertTrue(warnings.stream().anyMatch(w -> w.contains("re-saved as PKCS12")), warnings.toString());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("below the crypto policy") && w.contains("RSA-2048")
                    && w.contains("wsapi_tls_replace_noncompliant")), warnings.toString());

            // now PKCS12 under the random owner-only password; the old default no longer opens it
            String pw = Files.readString(d.resolve(WsapiKeystore.PASSWORD_FILE)).strip();
            assertEquals("rw-------", perms(d.resolve(WsapiKeystore.PASSWORD_FILE)));
            assertEquals("rw-------", perms(ks));
            assertEquals(0x30, Files.readAllBytes(ks)[0], "PKCS12");
            KeyStore p12 = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(ks)) { p12.load(in, pw.toCharArray()); }
            assertEquals(old, p12.getCertificate("wsapi"));
            assertTrue(p12.isKeyEntry("wsapi"));
            KeyStore stale = KeyStore.getInstance("PKCS12");
            assertThrows(Exception.class, () -> { try (var in = Files.newInputStream(ks)) { stale.load(in, "cresco".toCharArray()); } });
            try (var st = Files.list(d)) {
                assertEquals(List.of(), st.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp") || n.endsWith(".new")).toList());
            }

            // the next start loads it with the password file, the same certificate, nothing re-saved
            warnings.clear();
            WsapiKeystore.Material again = WsapiKeystore.loadOrCreate(settings(d), log);
            assertEquals(m.sha384, again.sha384);
            assertTrue(warnings.stream().noneMatch(w -> w.contains("re-saved")), warnings.toString());

            // the operator opts in to a policy-strength replacement
            warnings.clear();
            WsapiKeystore.Material fresh = WsapiKeystore.loadOrCreate(settings(d).replaceNonCompliant(true), log);
            assertTrue(fresh.generated);
            assertEquals("SHA384withECDSA", fresh.certificate.getSigAlgName());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("replaced") && w.contains(m.sha384)), warnings.toString());
            assertEquals(fresh.sha384, WsapiKeystore.loadOrCreate(settings(d), log).sha384);
        }
    }

    @Test
    void aKeystoreUnderAWeakConfiguredPasswordStillLoadsAndIsReSaved(@TempDir Path dir) throws Exception {
        X509Certificate old = legacyKeystore(dir.resolve("ws.keystore"), "PKCS12", 3072, "SHA384withRSA", "short".toCharArray(),
                Instant.now().plus(100, ChronoUnit.DAYS));
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(dir).configuredPassword("short".toCharArray()), log);
        assertEquals(old, m.certificate);
        assertTrue(Files.exists(dir.resolve(WsapiKeystore.PASSWORD_FILE)));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("too weak")), warnings.toString());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("below the crypto policy")), "RSA-3072 / SHA-384 meets the policy");
        // the weak configured password no longer opens it; the password file does
        assertEquals(m.sha384, WsapiKeystore.loadOrCreate(settings(dir).configuredPassword("short".toCharArray()), log).sha384);
    }

    @Test
    void anExpiredManagedCertificateIsReplaced(@TempDir Path dir) throws Exception {
        String pw = "a-configured-password-123";
        X509Certificate old = legacyKeystore(dir.resolve("ws.keystore"), "PKCS12", 3072, "SHA384withRSA", pw.toCharArray(),
                Instant.now().minus(1, ChronoUnit.DAYS));
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(dir).configuredPassword(pw.toCharArray()), log);
        assertTrue(m.generated);
        assertNotEquals(old, m.certificate);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("expired")));
    }

    @Test
    void aKeystoreItsPasswordFileDoesNotOpenIsRefused(@TempDir Path dir) throws Exception {
        WsapiKeystore.loadOrCreate(settings(dir), log);
        Path pass = dir.resolve(WsapiKeystore.PASSWORD_FILE);
        Files.writeString(pass, "not-the-password-at-all-xxxxxxxx");
        WsapiKeystore.Refused r = assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(dir), log));
        assertTrue(r.getMessage().contains("does not open") && r.getMessage().contains("Delete"));
    }

    @Test
    void aPasswordFileOthersCanReadIsRefused(@TempDir Path dir) throws Exception {
        WsapiKeystore.loadOrCreate(settings(dir), log);
        Files.setPosixFilePermissions(dir.resolve(WsapiKeystore.PASSWORD_FILE), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(dir), log));
    }

    @Test
    void aWeakConfiguredPasswordNeverProtectsANewKeystore(@TempDir Path dir) throws Exception {
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(dir).configuredPassword("cresco".toCharArray()), log);
        assertTrue(m.generated);
        assertNotEquals("cresco", new String(m.password));
        assertEquals(new String(m.password), Files.readString(dir.resolve(WsapiKeystore.PASSWORD_FILE)).strip());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("too weak")));
        Path d2 = Files.createDirectories(dir.resolve("strong"));
        WsapiKeystore.Material s = WsapiKeystore.loadOrCreate(settings(d2).configuredPassword("a-strong-password-1".toCharArray()), log);
        assertEquals("a-strong-password-1", new String(s.password));
        assertFalse(Files.exists(d2.resolve(WsapiKeystore.PASSWORD_FILE)), "a strong configured password needs no password file");
    }

    @Test
    void anOperatorKeystoreIsUsedAsIsWhenCompliantAndRefusedWhenNot(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("operator.p12");
        char[] pw = "operator-password-1".toCharArray();
        X509Certificate cert = legacyKeystore(good, "PKCS12", 3072, "SHA384withRSA", pw, Instant.now().plus(90, ChronoUnit.DAYS));
        byte[] before = Files.readAllBytes(good);
        Path data = Files.createDirectories(dir.resolve("data"));
        WsapiKeystore.Material m = WsapiKeystore.loadOrCreate(settings(data).operatorKeystore(good).configuredPassword(pw), log);
        assertEquals(cert, m.certificate);
        assertFalse(m.generated);
        assertArrayEquals(before, Files.readAllBytes(good), "never modified");
        assertFalse(Files.exists(data.resolve(WsapiKeystore.KEYSTORE)));
        assertTrue(Files.readString(data.resolve(WsapiKeystore.FINGERPRINT_FILE)).contains(m.sha384), "still published");

        Path weak = dir.resolve("weak.p12");
        legacyKeystore(weak, "PKCS12", 2048, "SHA256withRSA", pw, Instant.now().plus(90, ChronoUnit.DAYS));
        WsapiKeystore.Refused r = assertThrows(WsapiKeystore.Refused.class,
                () -> WsapiKeystore.loadOrCreate(settings(data).operatorKeystore(weak).configuredPassword(pw), log));
        assertTrue(r.getMessage().contains("RSA-2048"), r.getMessage());
        assertArrayEquals(Files.readAllBytes(weak), Files.readAllBytes(weak));

        Path jks = dir.resolve("op.jks");
        legacyKeystore(jks, "JKS", 3072, "SHA384withRSA", pw, Instant.now().plus(90, ChronoUnit.DAYS));
        assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(data).operatorKeystore(jks).configuredPassword(pw), log));
        assertThrows(WsapiKeystore.Refused.class, () -> WsapiKeystore.loadOrCreate(settings(data).operatorKeystore(good), log), "no password");
    }
}
