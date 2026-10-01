package org.example.cryptoService;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class CryptoService {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public KeyPair generateKeyPair(int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize);
        return keyPairGenerator.generateKeyPair();
    }

    public X509Certificate generateCertificate (
        PublicKey publicKey, String clientName, String issuerName, PrivateKey issuerKey, long certValidityPeriod
    ) throws Exception {
        X500Name issuer = new X500Name("CN=" + issuerName);
        X500Name subject = new X500Name("CN=" + clientName);
        BigInteger serialNumber = new BigInteger(128, new SecureRandom());
        Date notBefore = Date.from(Instant.now());
        Date notAfter = Date.from(Instant.now().plus(certValidityPeriod, ChronoUnit.DAYS));

        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
            issuer, serialNumber, notBefore, notAfter, subject, publicKey
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .setProvider("BC").build(issuerKey);

        return new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certificateBuilder.build(signer));
    }

}
