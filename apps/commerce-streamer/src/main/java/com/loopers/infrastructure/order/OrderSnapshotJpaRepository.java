package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSnapshotJpaRepository extends JpaRepository<OrderSnapshotJpaEntity, String> {
}
