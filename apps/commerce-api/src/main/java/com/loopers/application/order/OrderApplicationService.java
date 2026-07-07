package com.loopers.application.order;

import com.loopers.application.coupon.CouponApplicationService;
import com.loopers.domain.inventory.InventoryEntity;
import com.loopers.domain.inventory.InventoryRepository;
import com.loopers.domain.order.OrderCreatedEvent;
import com.loopers.domain.order.OrderEntity;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderSnapshot;
import com.loopers.domain.order.OrderSnapshotItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.product.ProductEntity;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class OrderApplicationService {

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CouponApplicationService couponApplicationService;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrderInfo createOrder(String userId, List<OrderItemCommand> commands, String couponId) {
        List<OrderSnapshotItem> snapshotItems = commands.stream()
                .map(cmd -> {
                    ProductEntity product = productRepository.find(cmd.productId())
                            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + cmd.productId() + "] 상품을 찾을 수 없습니다."));
                    long subtotal = product.getPrice() * cmd.quantity();
                    return new OrderSnapshotItem(product.getId(), product.getName(), product.getPrice(), cmd.quantity(), subtotal);
                })
                .toList();

        long originalAmount = snapshotItems.stream().mapToLong(OrderSnapshotItem::subtotal).sum();
        long discountAmount = couponId != null
                ? couponApplicationService.reserveCoupon(couponId, userId, originalAmount)
                : 0L;

        Map<String, Integer> productQuantities = commands.stream()
                .collect(Collectors.toMap(OrderItemCommand::productId, OrderItemCommand::quantity));

        List<String> productIds = productQuantities.keySet().stream().sorted().toList();
        List<InventoryEntity> inventories = inventoryRepository.findAllByProductIdsWithLock(productIds);
        if (inventories.size() != productIds.size()) {
            throw new CoreException(ErrorType.NOT_FOUND, "존재하지 않는 재고가 포함되어 있습니다.");
        }
        inventories.forEach(inventory -> {
            inventory.deduct(productQuantities.get(inventory.getProductId()));
            inventoryRepository.save(inventory);
        });

        OrderSnapshot snapshot = new OrderSnapshot(snapshotItems, originalAmount, discountAmount,
                originalAmount - discountAmount, couponId);
        OrderInfo order = OrderInfo.from(orderRepository.save(new OrderEntity(userId, snapshot)));
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(userId, order.orderId());
        outboxEventRepository.createAndSave(createdEvent, ORDER_EVENTS_TOPIC, order.orderId());
        eventPublisher.publishEvent(createdEvent);
        return order;
    }

    /** 결제 준비: 주문 비관적 락 + 소유권/PENDING 검증 후 결제 금액 반환.
     *  결제 코디네이터가 같은 트랜잭션에서 호출해 락+중복검사 원자성을 보장한다. */
    @Transactional
    public Long prepareForPayment(String userId, String orderId) {
        OrderEntity order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 가능한 주문 상태가 아닙니다.");
        }
        return order.finalAmount();
    }

    /** 결제 성공 Step 1: 주문 PAID 전이만 커밋. 쿠폰 확정은 별도 TX에서 처리한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completePaidOrder(String orderId) {
        OrderEntity order = findOrderOrThrow(orderId);
        order.pay();
        orderRepository.save(order);
    }

    /** 결제 실패 보상: 주문 CANCELLED 전이 + 재고 복원. (all-or-nothing, 새 트랜잭션)
     *  쿠폰 해제는 리스너가 별도 TX로 처리한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateFailedOrder(String orderId) {
        OrderEntity order = findOrderOrThrow(orderId);
        order.cancel();
        orderRepository.save(order);

        // 재고 복원도 차감(createOrder)과 동일하게 비관적 락으로 lost update를 방지한다.
        Map<String, Integer> restoreQuantities = order.getSnapshot().items().stream()
                .collect(Collectors.toMap(OrderSnapshotItem::productId, OrderSnapshotItem::quantity));
        List<String> productIds = restoreQuantities.keySet().stream().sorted().toList();
        List<InventoryEntity> inventories = inventoryRepository.findAllByProductIdsWithLock(productIds);
        if (inventories.size() != productIds.size()) {
            throw new CoreException(ErrorType.NOT_FOUND, "복원할 재고를 찾을 수 없습니다.");
        }
        inventories.forEach(inventory -> {
            inventory.restore(restoreQuantities.get(inventory.getProductId()));
            inventoryRepository.save(inventory);
        });
    }

    @Transactional(readOnly = true)
    public Optional<String> findCouponIdByOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> order.getSnapshot().couponId());
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(String authUserId, String orderId) {
        OrderEntity order = findOrderOrThrow(orderId);
        if (!order.isOwnedBy(authUserId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderInfo> getOrders(String userId, ZonedDateTime startAt, ZonedDateTime endAt, Pageable pageable) {
        return orderRepository.findAllByUserId(userId, startAt, endAt, pageable).map(OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public Page<OrderInfo> getAdminOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public OrderInfo getAdminOrder(String orderId) {
        return OrderInfo.from(findOrderOrThrow(orderId));
    }

    private OrderEntity findOrderOrThrow(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }
}
