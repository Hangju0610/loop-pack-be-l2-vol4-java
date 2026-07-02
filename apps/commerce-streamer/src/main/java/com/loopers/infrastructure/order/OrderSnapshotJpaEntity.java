package com.loopers.infrastructure.order;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.ZonedDateTime;

@Immutable
@Entity
@Table(name = "orders")
@Getter
public class OrderSnapshotJpaEntity extends BaseJpaEntity {

    @Column(name = "ref_user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String snapshot;

    protected OrderSnapshotJpaEntity() {}

    public OrderSnapshotJpaEntity(String id, String userId, String status, String snapshot) {
        super(id, null);
        this.userId = userId;
        this.status = status;
        this.snapshot = snapshot;
    }

    @Override
    protected String idCode() {
        return "ORD";
    }

    public ZonedDateTime getDeletedAt() {
        return deletedAt;
    }
}
