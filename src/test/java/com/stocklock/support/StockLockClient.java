package com.stocklock.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal JSON-over-HTTP client for one StockLock instance; thread-safe. */
public class StockLockClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;

    public StockLockClient(int port) {
        this("http://localhost:" + port);
    }

    public StockLockClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public record Response(int status, JsonNode body, HttpHeaders headers) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String text(String field) {
            return body.path(field).asText();
        }
    }

    // ---- domain helpers -------------------------------------------------------------------------

    public long createProduct(int stock) {
        return createProduct(stock, "19.99");
    }

    public long createProduct(int stock, String price) {
        Response response = post("/api/products", Map.of(
                "sku", "SKU-" + UUID.randomUUID(),
                "name", "Test product",
                "price", price,
                "initialQuantity", stock), Map.of());
        if (response.status() != 201) {
            throw new IllegalStateException("Product creation failed: " + response);
        }
        return response.body().get("id").asLong();
    }

    public Response reserve(long productId, String userId, int quantity) {
        return reserve(productId, userId, quantity, UUID.randomUUID().toString());
    }

    public Response reserve(long productId, String userId, int quantity, String idempotencyKey) {
        return post("/api/reservations",
                Map.of("productId", productId, "userId", userId, "quantity", quantity),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    /** Reserves and returns the reservation id, failing if the reservation is not created. */
    public String reserveOrFail(long productId, String userId, int quantity) {
        Response response = reserve(productId, userId, quantity);
        if (response.status() != 201) {
            throw new IllegalStateException("Reservation failed: " + response);
        }
        return response.text("reservationId");
    }

    public JsonNode inventory(long productId) {
        return get("/api/products/" + productId + "/inventory").body();
    }

    public Response getReservation(String reservationId) {
        return get("/api/reservations/" + reservationId);
    }

    public Response cancelReservation(String reservationId) {
        return post("/api/reservations/" + reservationId + "/cancel", null, Map.of());
    }

    public Response createOrder(String userId, String... reservationIds) {
        return post("/api/orders", Map.of("userId", userId, "reservationIds", List.of(reservationIds)), Map.of());
    }

    public Response confirmOrder(String orderId) {
        return post("/api/orders/" + orderId + "/confirm", null, Map.of());
    }

    public Response cancelOrder(String orderId) {
        return post("/api/orders/" + orderId + "/cancel", null, Map.of());
    }

    // ---- raw HTTP -------------------------------------------------------------------------------

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        try {
            String json = body == null ? "" : JSON.writeValueAsString(body);
            Map<String, String> allHeaders = new LinkedHashMap<>(headers);
            allHeaders.put("Content-Type", "application/json");
            return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .POST(HttpRequest.BodyPublishers.ofString(json)), allHeaders);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response send(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
        builder.timeout(Duration.ofSeconds(60));
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), parse(response.body()), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String body) {
        if (body.isEmpty()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException notJson) {
            return JSON.getNodeFactory().textNode(body); // e.g. the Swagger UI HTML page
        }
    }
}
