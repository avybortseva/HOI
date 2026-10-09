package org.example.transferService;

import java.nio.channels.SocketChannel;

public record TransferTask(
        SocketChannel clientChannel,
        String clientName,
        byte[] responseData
) {}
