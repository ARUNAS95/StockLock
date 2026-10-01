package com.stocklock.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RestockRequest(@NotNull @Positive @Max(1_000_000) Integer quantity) {
}
