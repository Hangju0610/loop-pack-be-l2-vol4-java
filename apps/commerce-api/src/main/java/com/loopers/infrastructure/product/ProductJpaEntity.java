package com.loopers.infrastructure.product;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "product",
    indexes = {
        @Index(name = "idx_product_deleted_at_price",       columnList = "deleted_at, price"),
        @Index(name = "idx_product_brand_deleted_at_price", columnList = "ref_brand_id, deleted_at, price"),
        @Index(name = "idx_product_brand_deleted_at",       columnList = "ref_brand_id, deleted_at")
    }
)
@Getter
public class ProductJpaEntity extends BaseJpaEntity {

    @Column(name = "ref_brand_id", nullable = false)
    private String brandId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Long price;

    protected ProductJpaEntity() {}

    @Override
    protected String idCode() {
        return "PRD";
    }

    ProductJpaEntity(String id, String brandId, String name, String description, Long price, ZonedDateTime deletedAt) {
        super(id, deletedAt);
        this.brandId = brandId;
        this.name = name;
        this.description = description;
        this.price = price;
    }
}
