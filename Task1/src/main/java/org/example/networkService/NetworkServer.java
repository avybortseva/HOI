package org.example.networkService;

import org.example.stateManager.KeyManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class NetworkServer implements Runnable {
    private final int port;
    private final KeyManager keyManager;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running = true;

    public NetworkServer(int port, KeyManager keyManager) {
        this.port = port;
        this.keyManager = keyManager;
    }


    @Override
    public void run() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            while (running && !Thread.currentThread().isInterrupted()) {
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        readClientName(key);
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Server error: " + e.getMessage());
            }
        } finally {
            closeServer();
        }

    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new ByteArrayOutputStream());
        }
    }

    private void readClientName(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        ByteArrayOutputStream buffer = (ByteArrayOutputStream) key.attachment();
        ByteBuffer readBuffer = ByteBuffer.allocate(256);

        try {
            int bytesRead = client.read(readBuffer);
            if (bytesRead == -1) {
                client.close();
                return;
            }

            readBuffer.flip();
            while (readBuffer.hasRemaining()) {
                byte b = readBuffer.get();
                if (b == 0) {
                    String clientName = buffer.toString(StandardCharsets.US_ASCII);
                    key.cancel();
                    keyManager.processRequest(clientName, client);
                    return;
                } else {
                    buffer.write(b);
                }
            }
        } catch (IOException e) {
            System.err.println("Reading from client error: " + e.getMessage());
            closeChannel(client);
        }
    }

    private void closeChannel(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException _) {}
    }

    private void closeServer() {
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException _) {}
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException _) {}
    }

    public void stop() {
        this.running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
