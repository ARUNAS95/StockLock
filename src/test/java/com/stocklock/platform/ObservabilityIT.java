package com.stocklock.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ObservabilityIT extends IntegrationTest {

    @Test
    void healthAndProbesAreUp() {
        Response health = api.get("/actuator/health");

        assertThat(health.status()).isEqualTo(200);
        assertThat(health.text("status")).isEqualTo("UP");
        assertThat(health.body().path("components").has("db")).isTrue();
        assertThat(api.get("/actuator/health/readiness").text("status")).isEqualTo("UP");
        assertThat(api.get("/actuator/health/liveness").text("status")).isEqualTo("UP");
    }

    @Test
    void domainMetricsAreExposed() throws Exception {
        long productId = api.createProduct(1);
        api.reserve(productId, "alice", 1);
        api.reserve(productId, "bob", 1);

        assertThat(api.get("/actuator/metrics/reservations.created").body().path("measurements").get(0)
                .path("value").asDouble()).isPositive();
        assertThat(api.get("/actuator/metrics/inventory.out_of_stock").status()).isEqualTo(200);

        String prometheus = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(prometheus)
                // OpenMetrics reserves the "_created" suffix, so reservations.created is exported as reservations_total.
                .contains("reservations_total{")
                .contains("reservations_failed_total{application=\"stocklock\"")
                .contains("inventory_out_of_stock_total")
                .contains("outbox_pending");
    }

    @Test
    void openApiDocumentDescribesTheApi() {
        Response docs = api.get("/v3/api-docs");

        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.body().path("paths").has("/api/reservations")).isTrue();
        assertThat(docs.body().path("paths").has("/api/orders/{orderId}/confirm")).isTrue();
        assertThat(api.get("/swagger-ui/index.html").status()).isEqualTo(200);
    }
}
