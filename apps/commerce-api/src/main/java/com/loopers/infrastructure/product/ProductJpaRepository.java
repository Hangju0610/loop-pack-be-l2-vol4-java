package com.loopers.infrastructure.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, String> {
    Optional<ProductJpaEntity> findByIdAndDeletedAtIsNull(String id);
    Page<ProductJpaEntity> findAllByDeletedAtIsNull(Pageable pageable);
    Page<ProductJpaEntity> findAllByBrandIdAndDeletedAtIsNull(String brandId, Pageable pageable);

    @Query("SELECT p.id FROM ProductJpaEntity p WHERE p.brandId = :brandId AND p.deletedAt IS NULL")
    List<String> findIdsByBrandId(@Param("brandId") String brandId);

    List<ProductJpaEntity> findAllByIdInAndDeletedAtIsNull(List<String> ids);
}
