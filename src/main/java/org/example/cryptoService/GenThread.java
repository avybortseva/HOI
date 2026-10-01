package org.example.cryptoService;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

public class GenThread implements Runnable {
    private final String clientName;
    private final CryptoService cryptoService;
    private final String issuerName;
    private final PrivateKey serverPrivateKey;
    private final CompletableFuture<byte[]> resultFuture;
    private final int keySize = 8192;
    private final long certValidityPeriod= 30;

    public GenThread(String clientName, CryptoService cryptoService, String issuerName, PrivateKey serverPrivateKey, CompletableFuture<byte[]> resultFuture) {
        this.clientName = clientName;
        this.cryptoService = cryptoService;
        this.issuerName = issuerName;
        this.serverPrivateKey = serverPrivateKey;
        this.resultFuture = resultFuture;
    }

    @Override
    public void run() {
        try {
            KeyPair keyPair = cryptoService.generateKeyPair(keySize);
            X509Certificate certificate = cryptoService.generateCertificate(
                    keyPair.getPublic(), clientName, issuerName, serverPrivateKey, certValidityPeriod);
            byte[] responseData = serialize(keyPair.getPrivate().getEncoded(), certificate.getEncoded());

            if (resultFuture != null) {
                resultFuture.complete(responseData);
            }
        } catch (Exception e) {
            System.err.println("Generation error from" + clientName + ": " + e.getMessage());
            if (resultFuture != null) {
                resultFuture.completeExceptionally(e);
            }
        }
    }

    private byte[] serialize(byte[] privateKey, byte[] certificate) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(byteStream);

        dataStream.writeInt(privateKey.length);
        dataStream.write(privateKey);

        dataStream.writeInt(certificate.length);
        dataStream.write(certificate);

        dataStream.flush();

        return byteStream.toByteArray();
    }
}
