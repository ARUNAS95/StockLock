package com.stocklock.order;

import com.stocklock.common.error.InvalidStateTransitionException;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.events.EventTypes;
import com.stocklock.events.OrderEventPayload;
import com.stocklock.inventory.InventoryService;
import com.stocklock.order.dto.CreateOrderRequest;
import com.stocklock.order.dto.OrderResponse;
import com.stocklock.outbox.OutboxWriter;
import com.stocklock.product.Product;
import com.stocklock.product.ProductRepository;
import com.stocklock.reservation.Reservation;
import com.stocklock.reservation.ReservationEvents;
import com.stocklock.reservation.ReservationExpiredException;
import com.stocklock.reservation.ReservationNotFoundException;
import com.stocklock.reservation.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders turn reservations into sales. Lock order inside every transaction is
 * order row → reservation rows (ascending id) → inventory rows (ascending product id), the same
 * hierarchy used by reservation cancellation and expiration, so these flows cannot deadlock each
 * other.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final ReservationEvents reservationEvents;
    private final OutboxWriter outboxWriter;
    private final StockLockMetrics metrics;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                        ReservationRepository reservationRepository, ProductRepository productRepository,
                        InventoryService inventoryService, ReservationEvents reservationEvents,
                        OutboxWriter outboxWriter, StockLockMetrics metrics, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.reservationEvents = reservationEvents;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Groups ACTIVE, unexpired reservations of one user into an order. Stock is not touched yet; it
     * stays reserved until the order is confirmed or cancelled (or the reservations expire).
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Instant now = Instant.now(clock);
        List<Reservation> reservations = lockReservations(request.reservationIds());
        for (Reservation reservation : reservations) {
            if (!reservation.getUserId().equals(request.userId())) {
                throw new ReservationOwnerMismatchException(reservation.getReservationId(), request.userId());
            }
            requireActiveAndUnexpired(reservation, now);
        }
        // The reservation rows are locked, so this check cannot race with another order creation;
        // uk_order_items_reservation backs it up at the database level.
        orderItemRepository.findByReservationIdIn(request.reservationIds()).stream().findFirst().ifPresent(item -> {
            throw new ReservationAlreadyOrderedException(item.getReservationId());
        });

        Map<Long, Product> products = productRepository
                .findAllById(reservations.stream().map(Reservation::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Order.Line> lines = reservations.stream()
                .map(r -> new Order.Line(r.getReservationId(), r.getProductId(), r.getQuantity(),
                        products.get(r.getProductId()).getPrice()))
                .toList();

        Order order = orderRepository.save(Order.create(UUID.randomUUID(), request.userId(), lines, now));
        recordEvent(EventTypes.ORDER_CREATED, order);
        metrics.orderCreated();
        log.info("Order created: orderId={} userId={} reservations={} total={}", order.getOrderId(),
                order.getUserId(), order.reservationIds(), order.getTotalAmount());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * Confirms the order: every reservation must still be ACTIVE and inside its hold period. All of
     * them are confirmed and their stock consumed in one transaction, or (on any failure) none are.
     */
    @Transactional
    public OrderResponse confirm(UUID orderId) {
        Instant now = Instant.now(clock);
        Order order = lockOrder(orderId);
        order.confirm(now);
        List<Reservation> reservations = lockReservations(order.reservationIds());
        for (Reservation reservation : reservations) {
            try {
                reservation.confirm(now);
            } catch (InvalidStateTransitionException e) {
                metrics.reservationConflict();
                throw e;
            }
        }
        inventoryService.consumeReserved(sumByProduct(reservations));
        recordEvent(EventTypes.ORDER_CONFIRMED, order);
        metrics.orderConfirmed();
        metrics.reservationsConfirmed(reservations.size());
        log.info("Order confirmed: orderId={} total={}", orderId, order.getTotalAmount());
        return OrderResponse.from(order);
    }

    /**
     * Cancels a CREATED order and releases the stock of its still-ACTIVE reservations. Reservations
     * that already expired or were cancelled individually have released their stock already and are
     * left alone, so stock is never released twice.
     */
    @Transactional
    public OrderResponse cancel(UUID orderId) {
        Instant now = Instant.now(clock);
        Order order = lockOrder(orderId);
        order.cancel(now);
        List<Reservation> active = lockReservations(order.reservationIds()).stream()
                .filter(Reservation::isActive)
                .toList();
        for (Reservation reservation : active) {
            reservation.cancel(now);
            reservationEvents.record(EventTypes.RESERVATION_CANCELLED, reservation);
        }
        inventoryService.release(sumByProduct(active));
        recordEvent(EventTypes.ORDER_CANCELLED, order);
        metrics.orderCancelled();
        metrics.reservationsCancelled(active.size());
        log.info("Order cancelled: orderId={} releasedReservations={}", orderId, active.size());
        return OrderResponse.from(order);
    }

    private Order lockOrder(UUID orderId) {
        return orderRepository.findByOrderIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private List<Reservation> lockReservations(Iterable<UUID> reservationIds) {
        Set<UUID> requested = new HashSet<>();
        reservationIds.forEach(requested::add);
        List<Reservation> locked = reservationRepository.findAllByReservationIdForUpdate(requested);
        if (locked.size() != requested.size()) {
            locked.forEach(r -> requested.remove(r.getReservationId()));
            throw new ReservationNotFoundException(requested.iterator().next());
        }
        return locked;
    }

    private static void requireActiveAndUnexpired(Reservation reservation, Instant now) {
        if (!reservation.isActive()) {
            throw new InvalidStateTransitionException("Reservation %s is %s and cannot be ordered"
                    .formatted(reservation.getReservationId(), reservation.getStatus()));
        }
        if (reservation.isExpiredAt(now)) {
            throw new ReservationExpiredException(reservation.getReservationId());
        }
    }

    private static Map<Long, Integer> sumByProduct(List<Reservation> reservations) {
        return reservations.stream()
                .collect(Collectors.groupingBy(Reservation::getProductId, Collectors.summingInt(Reservation::getQuantity)));
    }

    private void recordEvent(String eventType, Order order) {
        List<OrderEventPayload.Item> items = order.getItems().stream()
                .map(i -> new OrderEventPayload.Item(i.getReservationId(), i.getProductId(), i.getQuantity(),
                        i.getPrice()))
                .toList();
        outboxWriter.append(EventTypes.AGGREGATE_ORDER, order.getOrderId().toString(), eventType,
                new OrderEventPayload(order.getOrderId(), order.getUserId(), order.getStatus().name(),
                        order.getTotalAmount(), items));
    }
}
