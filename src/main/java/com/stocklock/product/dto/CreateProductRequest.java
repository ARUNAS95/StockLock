package com.stocklock.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may only contain letters, digits, '.', '_' and '-'")
        String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @PositiveOrZero @Max(1_000_000) Integer initialQuantity) {
}
