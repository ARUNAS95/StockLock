package com.stocklock.reservation;

import com.stocklock.reservation.dto.CreateReservationRequest;
import com.stocklock.reservation.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
@Tag(name = "Reservations")
public class ReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @Operation(summary = "Reserve stock",
            description = "Returns 201 when a reservation is created and 200 with header Idempotent-Replayed: true "
                    + "when the Idempotency-Key was already used for the same request.")
    public ResponseEntity<ReservationResponse> create(
            @Parameter(description = "Client-generated unique key; retries must reuse it", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {
        ReservationOutcome outcome = reservationService.reserve(request, idempotencyKey);
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(outcome.reservation());
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + outcome.reservation().reservationId()))
                .body(outcome.reservation());
    }

    @GetMapping("/{reservationId}")
    @Operation(summary = "Get a reservation")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return reservationService.get(reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    @Operation(summary = "Cancel an ACTIVE reservation and release its stock")
    public ReservationResponse cancel(@PathVariable UUID reservationId) {
        return reservationService.cancel(reservationId);
    }
}
