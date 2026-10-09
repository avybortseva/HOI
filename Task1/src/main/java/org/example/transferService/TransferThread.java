package org.example.transferService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

public class TransferThread implements Runnable {
    private final BlockingQueue<TransferTask> transferQueue;
    private final Selector selector;

    public TransferThread(BlockingQueue<TransferTask> transferQueue) throws IOException {
        this.transferQueue = transferQueue;
        this.selector = Selector.open();
    }


    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                drainNewTasks();

                if (selector.select(10) > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Transfer thread error: " + e.getMessage());
            }
        }
        closeSelector();
    }

    private void drainNewTasks() {
        TransferTask task;
        while ((task = transferQueue.poll()) != null) {
            SocketChannel channel = task.clientChannel();
            if (channel != null && channel.isOpen()) {
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(task.responseData());

                    channel.configureBlocking(false);
                    channel.write(buffer);

                    if (buffer.hasRemaining()) {
                        channel.register(selector, SelectionKey.OP_WRITE, buffer);
                    } else {
                        channel.close();
                    }
                } catch (IOException e) {
                    System.err.println("Initiating write error to " + task.clientName() + ": " + e.getMessage());
                    closeChannel(channel);
                }
            }
        }
    }

    private void closeChannel(SocketChannel channel) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException _) {}
    }

    private void handleWrite(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        try {
            socketChannel.write(buffer);

            if (!buffer.hasRemaining()) {
                key.cancel();
                socketChannel.close();
            }
        } catch (IOException e) {
            System.err.println("Writing to client error: " + e.getMessage());
            key.cancel();
            closeChannel(socketChannel);
        }
    }

    private void closeSelector() {
        try {
            selector.close();
        } catch (IOException _) {}
    }
}
