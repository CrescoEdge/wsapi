package io.cresco.wsapi.auth;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** A throwaway PKI made at test time: CAs, client certificates with Cresco subject alternative names, CRLs. */
final class TestPki {

    static final SecureRandom RANDOM = new SecureRandom();

    /** A CA: its key and self-signed certificate. */
    static final class Ca {
        final KeyPair key;
        final X509Certificate cert;

        Ca(KeyPair key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }
    }

    /** A client: its key and certificate (issued by a CA). */
    static final class Client {
        final KeyPair key;
        final X509Certificate cert;
        final Ca issuer;

        Client(KeyPair key, X509Certificate cert, Ca issuer) {
            this.key = key;
            this.cert = cert;
            this.issuer = issuer;
        }

        /** A PKCS12 key store with the key and chain (password "changeit"), for a client's key manager. */
        KeyStore keyStore() throws Exception {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("client", key.getPrivate(), "changeit".toCharArray(), new java.security.cert.Certificate[]{cert, issuer.cert});
            return ks;
        }
    }

    static KeyPair ecKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"), RANDOM);
        return g.generateKeyPair();
    }

    static Ca ca(String cn) throws Exception {
        KeyPair k = ecKey();
        X500Name name = new X500Name("CN=" + cn);
        Date from = new Date(System.currentTimeMillis() - 60_000L), to = new Date(System.currentTimeMillis() + 86_400_000L);
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, new BigInteger(64, RANDOM), from, to, name, k.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        X509Certificate c = new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA384withECDSA").build(k.getPrivate())));
        return new Ca(k, c);
    }

    /** A client certificate valid for {@code validMs} from now, with the given subject alternative name URIs. */
    static Client client(Ca ca, String cn, long validMs, String... uris) throws Exception {
        KeyPair k = ecKey();
        Date from = new Date(System.currentTimeMillis() - 60_000L), to = new Date(System.currentTimeMillis() + validMs);
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(ca.cert, new BigInteger(64, RANDOM), from, to,
                new X500Name("CN=" + cn), k.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        if (uris.length > 0) {
            List<GeneralName> names = new ArrayList<>();
            for (String u : uris) names.add(new GeneralName(GeneralName.uniformResourceIdentifier, u));
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(new GeneralName[0])));
        }
        X509Certificate c = new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA384withECDSA").build(ca.key.getPrivate())));
        return new Client(k, c, ca);
    }

    static Client client(Ca ca, String cn, String... uris) throws Exception {
        return client(ca, cn, 86_400_000L, uris);
    }

    /** A CRL of {@code ca} revoking the given certificates. */
    static X509CRL crl(Ca ca, X509Certificate... revoked) throws Exception {
        Date now = new Date();
        X509v2CRLBuilder b = new X509v2CRLBuilder(new X500Name(ca.cert.getSubjectX500Principal().getName()), now);
        b.setNextUpdate(new Date(now.getTime() + 3_600_000L));
        for (X509Certificate c : revoked) b.addCRLEntry(c.getSerialNumber(), now, CRLReason.keyCompromise);
        return new JcaX509CRLConverter().getCRL(b.build(new JcaContentSignerBuilder("SHA384withECDSA").build(ca.key.getPrivate())));
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    static Path writePem(Path file, X509Certificate... certs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : certs) sb.append(pem("CERTIFICATE", c.getEncoded()));
        return Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    static Path writeCrl(Path file, X509CRL crl) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, pem("X509 CRL", crl.getEncoded()), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
