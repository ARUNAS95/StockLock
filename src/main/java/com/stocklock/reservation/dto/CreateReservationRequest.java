package com.stocklock.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(
        @NotNull @Positive Long productId,
        @NotBlank @Size(max = 64) String userId,
        @NotNull @Positive @Max(1000) Integer quantity) {
}
