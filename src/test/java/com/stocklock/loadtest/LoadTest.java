package com.stocklock.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fires N simultaneous reservation requests (quantity 1, distinct users and idempotency keys) at one
 * or more StockLock instances, round-robin, then checks the inventory.
 *
 * <p>Uses only the JDK so it can run without a build:
 * <pre>
 * java src/test/java/com/stocklock/loadtest/LoadTest.java \
 *      --urls http://localhost:8081,http://localhost:8082,http://localhost:8083 --requests 1000 --stock 100
 * </pre>
 * It is also driven by {@code ReservationLoadIT} ({@code mvn verify -Pload}).
 */
public final class LoadTest {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern AVAILABLE = Pattern.compile("\"availableQuantity\"\\s*:\\s*(-?\\d+)");
    private static final Pattern RESERVED = Pattern.compile("\"reservedQuantity\"\\s*:\\s*(-?\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final List<String> baseUrls;

    public LoadTest(List<String> baseUrls) {
        this.baseUrls = List.copyOf(baseUrls);
    }

    public record Result(
            int requests,
            int stock,
            int instances,
            int succeeded,
            int outOfStock,
            Map<Integer, Integer> otherStatuses,
            int finalAvailable,
            int finalReserved,
            long wallMillis,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis) {

        public boolean inventoryCorrect() {
            return succeeded == Math.min(requests, stock)
                    && finalAvailable == stock - succeeded
                    && finalReserved == succeeded
                    && finalAvailable >= 0;
        }

        public String report() {
            return """
                    requests=%d stock=%d instances=%d
                      succeeded (201)      : %d
                      out of stock (409)   : %d
                      other                : %s
                      final available      : %d
                      final reserved       : %d
                      inventory correct    : %s
                      wall time            : %d ms
                      latency p50/p95/p99  : %.1f / %.1f / %.1f ms (max %.1f ms)"""
                    .formatted(requests, stock, instances, succeeded, outOfStock, otherStatuses, finalAvailable,
                            finalReserved, inventoryCorrect() ? "YES" : "NO", wallMillis, p50Millis, p95Millis,
                            p99Millis, maxMillis);
        }
    }

    public Result run(int requests, int stock) throws Exception {
        long productId = createProduct(stock);
        int[] statuses = new int[requests];
        long[] latencies = new long[requests];

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        long wallStart;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(requests);
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    HttpRequest request = reservationRequest(baseUrls.get(index % baseUrls.size()), productId, index);
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    statuses[index] = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    latencies[index] = System.nanoTime() - t0;
                    return null;
                }));
            }
            ready.await();
            wallStart = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;

        int succeeded = 0;
        int outOfStock = 0;
        Map<Integer, Integer> other = new TreeMap<>();
        for (int status : statuses) {
            switch (status) {
                case 201 -> succeeded++;
                case 409 -> outOfStock++;
                default -> other.merge(status, 1, Integer::sum);
            }
        }
        String inventory = get(baseUrls.getFirst() + "/api/products/" + productId + "/inventory");
        Arrays.sort(latencies);
        return new Result(requests, stock, baseUrls.size(), succeeded, outOfStock, other,
                extract(AVAILABLE, inventory), extract(RESERVED, inventory), wallMillis,
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                latencies[latencies.length - 1] / 1e6);
    }

    private HttpRequest reservationRequest(String baseUrl, long productId, int index) {
        String body = "{\"productId\":%d,\"userId\":\"load-user-%d\",\"quantity\":1}".formatted(productId, index);
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/reservations"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private long createProduct(int stock) throws Exception {
        String body = "{\"sku\":\"LOAD-%s\",\"name\":\"Load test product\",\"price\":9.99,\"initialQuantity\":%d}"
                .formatted(UUID.randomUUID(), stock);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrls.getFirst() + "/api/products"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Could not create product: " + response.statusCode() + " " + response.body());
        }
        return extract(ID, response.body());
    }

    private String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static int extract(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Unexpected response: " + json);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static double percentile(long[] sortedNanos, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, index)] / 1e6;
    }

    public static void main(String[] args) throws Exception {
        List<String> urls = List.of("http://localhost:8080");
        List<Integer> requestCounts = List.of(100, 500, 1000);
        Integer stock = null;
        for (int i = 0; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--urls" -> urls = List.of(args[i + 1].split(","));
                case "--requests" -> requestCounts = Arrays.stream(args[i + 1].split(",")).map(Integer::valueOf).toList();
                case "--stock" -> stock = Integer.valueOf(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        LoadTest loadTest = new LoadTest(urls);
        boolean allCorrect = true;
        for (int requests : requestCounts) {
            int productStock = stock != null ? stock : Math.max(1, requests / 10);
            Result result = loadTest.run(requests, productStock);
            System.out.println(result.report());
            System.out.println();
            allCorrect &= result.inventoryCorrect() && result.otherStatuses().isEmpty();
        }
        System.exit(allCorrect ? 0 : 1);
    }
}
