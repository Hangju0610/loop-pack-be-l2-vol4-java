package com.loopers.domain.order;

import java.util.Optional;

public interface OrderSnapshotRepository {
    Optional<OrderSnapshot> findByOrderId(String orderId);
}
