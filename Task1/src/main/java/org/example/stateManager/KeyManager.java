package org.example.stateManager;

import org.example.cryptoService.CryptoService;
import org.example.cryptoService.GenThread;
import org.example.transferService.TransferTask;
import org.example.transferService.TransferThread;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.security.PrivateKey;
import java.util.concurrent.*;

public class KeyManager {
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();
    private final ExecutorService generationPool;
    private final ExecutorService transferPool;
    private final BlockingQueue<TransferTask> transferQueue = new LinkedBlockingQueue<>();

    private final CryptoService cryptoService;
    private final String issuerName;
    private final PrivateKey serverPrivateKey;

    public KeyManager(int genThreadsCount, int transferThreadsCount, CryptoService cryptoService, String issuerName, PrivateKey serverPrivateKey) {
        this.generationPool = Executors.newFixedThreadPool(genThreadsCount);
        this.transferPool = Executors.newFixedThreadPool(transferThreadsCount);
        this.cryptoService = cryptoService;
        this.issuerName = issuerName;
        this.serverPrivateKey = serverPrivateKey;

        for (int i = 0; i < transferThreadsCount; i++) {
            try {
                transferPool.submit(new TransferThread(transferQueue));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void processRequest(String clientName, SocketChannel clientChannel) {
        CompletableFuture<byte[]> future = cache.computeIfAbsent(clientName, name -> {
            CompletableFuture<byte[]> newFuture = new CompletableFuture<>();

            GenThread task = new GenThread(
                    name, cryptoService, issuerName, serverPrivateKey, newFuture);
            generationPool.submit(task);
            return newFuture;
        });

        future.whenComplete((responseData, ex) -> {
            if (ex != null) {
                System.err.println("Processing request error for " + clientName + ": " + ex.getMessage());
                try {
                    if (clientChannel != null && clientChannel.isOpen()) {
                        clientChannel.close();
                    }
                } catch (IOException e) {}
            } else {
                try {
                    transferQueue.put(new TransferTask(clientChannel, clientName, responseData));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    public void shutdown() {
        generationPool.shutdown();
        transferPool.shutdown();
    }
}
