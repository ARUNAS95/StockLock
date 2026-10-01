package com.stocklock.sales;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Sales (read model)")
public class SalesController {

    private final SalesProjection salesProjection;

    public SalesController(SalesProjection salesProjection) {
        this.salesProjection = salesProjection;
    }

    @GetMapping("/api/products/{productId}/sales")
    @Operation(summary = "Units sold and revenue, built asynchronously from OrderConfirmed events (eventually consistent)")
    public SalesProjection.ProductSales get(@PathVariable long productId) {
        return salesProjection.get(productId);
    }
}
