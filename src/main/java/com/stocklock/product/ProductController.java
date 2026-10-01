package com.stocklock.product;

import com.stocklock.common.web.PageResponse;
import com.stocklock.product.dto.CreateProductRequest;
import com.stocklock.product.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @Operation(summary = "Create a product together with its initial inventory")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse product = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
    }

    @GetMapping
    @Operation(summary = "List products")
    public PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.list(PageRequest.of(page, size, Sort.by("id")));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get a product")
    public ProductResponse get(@PathVariable long productId) {
        return productService.get(productId);
    }
}
