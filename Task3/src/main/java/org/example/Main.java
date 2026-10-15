
package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newHttpClient();

    private static final Set<String> VISITED =
            ConcurrentHashMap.newKeySet();

    private static final List<String> MESSAGES =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static ExecutorService executor;
    private static URI serverAddress;

    record ServerResponse(
            String message,
            List<String> successors
    ) {}

    public static void main(String[] args) {
        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : 8080;

        serverAddress = URI.create("http://localhost:" + port + "/");

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            executor = pool;

            crawl("/").join();

            MESSAGES.stream()
                    .sorted()
                    .forEach(System.out::println);

            System.out.println("All messages: " + MESSAGES.size());
            System.out.println("All visited paths: " + VISITED.size());
        } catch (Exception e) {
            System.err.println("Running error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static CompletableFuture<Void> crawl(String path) {
        if (!VISITED.add(path)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture
                .supplyAsync(() -> fetch(path), executor)
                .thenCompose(response -> {
                    MESSAGES.add(response.message());

                    List<CompletableFuture<Void>> tasks =
                            response.successors().stream()
                                    .map(Main::crawl)
                                    .toList();

                    return CompletableFuture.allOf(
                            tasks.toArray(new CompletableFuture<?>[0])
                    );
                });
    }

    private static ServerResponse fetch(String path) {
        URI uri = serverAddress.resolve(
                path.startsWith("/") ? path.substring(1) : path
        );

        if (path.equals("/")) {
            uri = serverAddress;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IOException(
                        "HTTP " + response.statusCode() + " для " + uri
                );
            }

            return MAPPER.readValue(
                    response.body(),
                    ServerResponse.class
            );

        } catch (IOException e) {
            throw new RuntimeException(
                    "Не удалось обработать " + uri, e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Запрос прерван: " + uri, e
            );
        }
    }
}
