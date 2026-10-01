package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.idempotency.IdempotencyCache;
import com.stocklock.idempotency.IdempotencyKeyReusedException;
import com.stocklock.idempotency.IdempotencyService;
import com.stocklock.idempotency.RequestFingerprint;
import com.stocklock.inventory.InventoryService;
import com.stocklock.inventory.OutOfStockException;
import com.stocklock.reservation.dto.CreateReservationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReservationServiceTest {

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final IdempotencyCache idempotencyCache = mock(IdempotencyCache.class);
    private final ReservationEvents events = mock(ReservationEvents.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReservationService service;

    private final CreateReservationRequest request = new CreateReservationRequest(5L, "user-1", 1);
    private final String hash = RequestFingerprint.of(5L, "user-1", 1);

    ReservationServiceTest() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        StockLockProperties properties = new StockLockProperties(
                new StockLockProperties.Reservation(Duration.ofMinutes(5), Duration.ofSeconds(5), 100),
                new StockLockProperties.Idempotency(Duration.ofHours(24), Duration.ofHours(1)),
                new StockLockProperties.Outbox(Duration.ofSeconds(1), 10, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(60)),
                new StockLockProperties.Kafka("r", "o", 3),
                new StockLockProperties.Scheduling(false));
        service = new ReservationService(reservationRepository, inventoryService, idempotencyService,
                idempotencyCache, events, new StockLockMetrics(registry), properties, txManager, Clock.systemUTC());
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cacheHitReplaysWithoutOpeningAWriteTransaction() {
        Reservation existing = Reservation.create(UUID.randomUUID(), 5L, "user-1", 1, Instant.now(),
                Duration.ofMinutes(5));
        when(idempotencyCache.find("key-1"))
                .thenReturn(Optional.of(new IdempotencyCache.Entry(hash, existing.getReservationId())));
        when(reservationRepository.findByReservationId(existing.getReservationId())).thenReturn(Optional.of(existing));

        ReservationOutcome outcome = service.reserve(request, "key-1");

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.reservation().reservationId()).isEqualTo(existing.getReservationId());
        verifyNoInteractions(txManager, inventoryService);
    }

    @Test
    void cacheHitWithDifferentPayloadIsRejected() {
        when(idempotencyCache.find("key-1"))
                .thenReturn(Optional.of(new IdempotencyCache.Entry("other-hash", UUID.randomUUID())));

        assertThatThrownBy(() -> service.reserve(request, "key-1")).isInstanceOf(IdempotencyKeyReusedException.class);
        verifyNoInteractions(inventoryService);
    }

    @Test
    void cacheMissCreatesReservationAndPopulatesCacheAfterCommit() {
        when(idempotencyCache.find(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.claim(any(), any(), any(), any()))
                .thenAnswer(inv -> new IdempotencyService.Claim(true, inv.getArgument(3)));

        ReservationOutcome outcome = service.reserve(request, "key-1");

        assertThat(outcome.replayed()).isFalse();
        verify(inventoryService).reserve(5L, 1);
        verify(idempotencyCache).remember("key-1", hash, outcome.reservation().reservationId());
        assertThat(registry.get("reservations.created").counter().count()).isEqualTo(1);
    }

    @Test
    void outOfStockIsCountedAndNotCached() {
        when(idempotencyCache.find(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.claim(any(), any(), any(), any()))
                .thenAnswer(inv -> new IdempotencyService.Claim(true, inv.getArgument(3)));
        org.mockito.Mockito.doThrow(new OutOfStockException("SKU", 1)).when(inventoryService).reserve(5L, 1);

        assertThatThrownBy(() -> service.reserve(request, "key-1")).isInstanceOf(OutOfStockException.class);

        verify(idempotencyCache, never()).remember(any(), any(), any());
        verify(reservationRepository, never()).save(any());
        verify(txManager).rollback(any());
        assertThat(registry.get("reservations.failed").tag("reason", "OUT_OF_STOCK").counter().count())
                .isEqualTo(1);
    }
}
