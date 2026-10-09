package org.example;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class CryptoClient {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Not enough arguments");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args[2];
        int delaySeconds = 0;
        boolean simulateCrash = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--delay") && (i + 1 < args.length)) {
                delaySeconds = Integer.parseInt(args[i + 1]);
            } else if (args[i].equals("--crash")) {
                simulateCrash = true;
            }
        }

        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.write(name.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.flush();

            if (simulateCrash) {
                System.out.println("Simulating crash...");
                System.exit(1);
            }

            if (delaySeconds > 0) {
                System.out.println("Simulating slow client...");
                Thread.sleep(delaySeconds * 1000L);
            }

            int keyLength = in.readInt();
            byte[] keyBytes = in.readNBytes(keyLength);
            int certLength = in.readInt();
            byte[] certBytes = in.readNBytes(certLength);

            saveFile(name + ".key", keyBytes);
            saveFile(name + ".crt", certBytes);



        } catch (Exception e) {
            System.err.println("Client error: " + e);
        }
    }

    private static void saveFile(String filename, byte[] data) throws IOException {
        Files.write(Paths.get(filename), data);
    }
}
