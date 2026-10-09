package org.example;

import org.example.cryptoService.CryptoService;
import org.example.networkService.NetworkServer;
import org.example.stateManager.KeyManager;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyPair;
import java.security.PrivateKey;

public class ServerMain {
    static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Not enough arguments");
            return;
        }

        int port = Integer.parseInt(args[0]);
        int genThreads = Integer.parseInt(args[1]);
        String issuerName = args[2];
        int transferThreads = 2;

        try {
            CryptoService cryptoService = new CryptoService();
            byte[] keyBytes = Files.readAllBytes(Paths.get("ca.key"));
            PrivateKey serverPrivateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            KeyManager keyManager = new KeyManager(
                genThreads, transferThreads, cryptoService, issuerName, serverPrivateKey);
            NetworkServer server = new NetworkServer(port, keyManager);
            Thread serverThread = new Thread(server);
            serverThread.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                keyManager.shutdown();
            }));

        } catch (Exception e) {
            System.err.println("Start service error: " + e.getMessage());
        }
    }
}
