package com.loopers.infrastructure.like;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(
        name = "likes",
        uniqueConstraints = @UniqueConstraint(name = "unique_likes_user_product", columnNames = {"user_id", "product_id"}),
        indexes = {
            // 사용자 좋아요 목록 페이지네이션
            @Index(name = "idx_likes_user_id_deleted_at",    columnList = "user_id, deleted_at"),
            // 상품 삭제 시 좋아요 일괄 soft delete
            @Index(name = "idx_likes_product_id_deleted_at", columnList = "product_id, deleted_at")
        }
)
@Getter
public class LikeJpaEntity extends BaseJpaEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    protected LikeJpaEntity() {}

    LikeJpaEntity(Long id, Long userId, Long productId, ZonedDateTime deletedAt) {
        super(id, deletedAt);
        this.userId = userId;
        this.productId = productId;
    }
}
