package com.stocklock.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.common.scheduling.BackgroundJobs;
import com.stocklock.support.Concurrently;
import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import com.stocklock.support.StockSnapshot;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderFlowIT extends IntegrationTest {

    @Autowired
    private BackgroundJobs backgroundJobs;

    @Test
    void confirmingAnOrderConsumesReservedStock() {
        long widget = api.createProduct(10, "4.00");
        long gadget = api.createProduct(5, "10.50");
        String r1 = api.reserveOrFail(widget, "alice", 3);
        String r2 = api.reserveOrFail(gadget, "alice", 2);

        Response created = api.createOrder("alice", r1, r2);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("CREATED");
        assertThat(created.body().get("totalAmount").decimalValue()).isEqualByComparingTo("33.00");

        Response confirmed = api.confirmOrder(created.text("orderId"));

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.text("status")).isEqualTo("CONFIRMED");
        assertThat(api.getReservation(r1).text("status")).isEqualTo("CONFIRMED");
        assertThat(stock(widget)).extracting(StockSnapshot::available, StockSnapshot::reserved).containsExactly(7, 0);
        assertThat(stock(gadget)).extracting(StockSnapshot::available, StockSnapshot::reserved).containsExactly(3, 0);
        assertThat(api.get("/api/orders/" + created.text("orderId")).body().get("items")).hasSize(2);
    }

    @Test
    void cancellingAnOrderReleasesItsReservations() {
        long productId = api.createProduct(10);
        String reservationId = api.reserveOrFail(productId, "alice", 4);
        String orderId = api.createOrder("alice", reservationId).text("orderId");

        Response cancelled = api.cancelOrder(orderId);

        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        assertThat(api.getReservation(reservationId).text("status")).isEqualTo("CANCELLED");
        assertThat(stock(productId).available()).isEqualTo(10);
        assertThat(api.confirmOrder(orderId).text("code")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void confirmedOrderCannotBeConfirmedOrCancelledAgain() {
        long productId = api.createProduct(10);
        String orderId = api.createOrder("alice", api.reserveOrFail(productId, "alice", 1)).text("orderId");
        api.confirmOrder(orderId);

        assertThat(api.confirmOrder(orderId).status()).isEqualTo(409);
        assertThat(api.cancelOrder(orderId).text("code")).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(stock(productId)).extracting(StockSnapshot::available, StockSnapshot::reserved).containsExactly(9, 0);
    }

    @Test
    void confirmAfterReservationDeadlineFailsAtomicallyAndSweeperReleasesStock() {
        long productId = api.createProduct(10);
        String fresh = api.reserveOrFail(productId, "alice", 1);
        String orderId = api.createOrder("alice", fresh).text("orderId");
        clock.advance(Duration.ofMinutes(6));

        Response response = api.confirmOrder(orderId);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("code")).isEqualTo("RESERVATION_EXPIRED");
        assertThat(api.get("/api/orders/" + orderId).text("status")).as("rolled back").isEqualTo("CREATED");
        assertThat(stock(productId).reserved()).isEqualTo(1);

        backgroundJobs.expireReservations();
        assertThat(stock(productId)).extracting(StockSnapshot::available, StockSnapshot::reserved).containsExactly(10, 0);
    }

    @Test
    void reservationCanBackOnlyOneOrder() {
        long productId = api.createProduct(10);
        String reservationId = api.reserveOrFail(productId, "alice", 1);

        List<Integer> statuses = Concurrently.run(10, i -> api.createOrder("alice", reservationId).status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
    }

    @Test
    void cannotOrderAnotherUsersReservation() {
        long productId = api.createProduct(10);
        String reservationId = api.reserveOrFail(productId, "alice", 1);

        Response response = api.createOrder("mallory", reservationId);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.text("code")).isEqualTo("RESERVATION_OWNER_MISMATCH");
    }

    @Test
    void cancelledReservationCannotBeOrdered() {
        long productId = api.createProduct(10);
        String reservationId = api.reserveOrFail(productId, "alice", 1);
        api.cancelReservation(reservationId);

        assertThat(api.createOrder("alice", reservationId).text("code")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void confirmRacingExpirationNeverDoubleCountsStock() {
        for (int round = 0; round < 10; round++) {
            clock.reset();
            long productId = api.createProduct(3);
            String reservationId = api.reserveOrFail(productId, "alice", 2);
            String orderId = api.createOrder("alice", reservationId).text("orderId");
            // Exactly at the deadline: confirm must refuse and the sweeper must release.
            clock.advance(Duration.ofMinutes(5));

            Concurrently.run(2, i -> {
                if (i == 0) {
                    return api.confirmOrder(orderId).status();
                }
                backgroundJobs.expireReservations();
                return 0;
            });

            StockSnapshot stock = stock(productId);
            assertThat(stock.reserved()).isZero();
            assertThat(stock.available() + stock.confirmedQuantity()).isEqualTo(3);
        }
    }

    @Test
    void unknownOrderIs404() {
        Response response = api.get("/api/orders/" + java.util.UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.text("code")).isEqualTo("ORDER_NOT_FOUND");
    }
}
