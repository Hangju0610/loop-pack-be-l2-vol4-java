package com.loopers.infrastructure.inventory;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(
        name = "inventory",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id"}, name="unique_product_id")
)
@Getter
public class InventoryJpaEntity extends BaseJpaEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    protected InventoryJpaEntity() {}

    InventoryJpaEntity(Long id, Long productId, Integer quantity, ZonedDateTime deletedAt) {
        super(id, deletedAt);
        this.productId = productId;
        this.quantity = quantity;
    }
}
