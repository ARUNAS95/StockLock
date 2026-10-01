package com.stocklock.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String userId,
        @NotEmpty @Size(max = 50) Set<@NotNull UUID> reservationIds) {
}
