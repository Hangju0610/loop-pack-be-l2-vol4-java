package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderSnapshot;
import com.loopers.domain.order.OrderSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderSnapshotRepositoryImpl implements OrderSnapshotRepository {

    private final OrderSnapshotJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<OrderSnapshot> findByOrderId(String orderId) {
        return jpaRepository.findById(orderId)
                .filter(entity -> entity.getDeletedAt() == null)
                .map(OrderSnapshotJpaEntity::getSnapshot)
                .map(this::parseSnapshot);
    }

    private OrderSnapshot parseSnapshot(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, OrderSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 snapshot 역직렬화 실패", e);
        }
    }
}
