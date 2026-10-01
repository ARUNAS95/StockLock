package com.stocklock.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductApiIT extends IntegrationTest {

    @Test
    void createsProductWithInventoryAndReadsItBack() {
        String sku = "SKU-" + UUID.randomUUID();
        Response created = api.post("/api/products", Map.of("sku", sku, "name", "Widget", "description", "A widget",
                "price", "12.50", "initialQuantity", 7), Map.of());

        assertThat(created.status()).isEqualTo(201);
        long id = created.body().get("id").asLong();
        assertThat(created.header("Location")).isEqualTo("/api/products/" + id);

        Response fetched = api.get("/api/products/" + id);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.text("sku")).isEqualTo(sku);
        assertThat(fetched.body().get("price").decimalValue()).isEqualByComparingTo("12.50");

        assertThat(api.inventory(id).get("availableQuantity").asInt()).isEqualTo(7);
        assertThat(api.inventory(id).get("reservedQuantity").asInt()).isZero();

        Response page = api.get("/api/products?page=0&size=100");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("totalElements").asLong()).isPositive();
    }

    @Test
    void rejectsDuplicateSkuViaUniqueConstraint() {
        String sku = "SKU-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("sku", sku, "name", "Widget", "price", "1.00", "initialQuantity", 1);
        assertThat(api.post("/api/products", body, Map.of()).status()).isEqualTo(201);

        Response duplicate = api.post("/api/products", body, Map.of());

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("code")).isEqualTo("DUPLICATE_SKU");
    }

    @Test
    void returnsStructuredValidationErrors() {
        Response invalid = api.post("/api/products",
                Map.of("sku", "", "name", "Widget", "price", "-1", "initialQuantity", -5), Map.of());

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.text("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(invalid.text("path")).isEqualTo("/api/products");
        assertThat(invalid.body().get("timestamp").isNull()).isFalse();
        assertThat(invalid.body().get("violations").findValuesAsText("field"))
                .contains("sku", "price", "initialQuantity");
    }

    @Test
    void unknownProductIs404() {
        Response missing = api.get("/api/products/999999999");

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(api.get("/api/products/999999999/inventory").text("code")).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void restockAddsAvailableStockAtomically() {
        long productId = api.createProduct(2);

        Response restocked = api.post("/api/products/" + productId + "/inventory/restock", Map.of("quantity", 3),
                Map.of());

        assertThat(restocked.status()).isEqualTo(200);
        assertThat(restocked.body().get("availableQuantity").asInt()).isEqualTo(5);
    }

    @Test
    void echoesCorrelationIdAndGeneratesRequestId() {
        Response response = api.post("/api/products", Map.of(), Map.of("X-Correlation-Id", "trace-abc-123"));

        assertThat(response.header("X-Correlation-Id")).isEqualTo("trace-abc-123");
        assertThat(response.header("X-Request-Id")).isNotBlank();
        assertThat(response.text("correlationId")).isEqualTo("trace-abc-123");
    }
}
