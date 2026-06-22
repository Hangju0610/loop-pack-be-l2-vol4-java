package com.loopers.application.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

public record ProductListCache(
        List<ProductInfo> content,
        long totalElements,
        int pageSize
) {
    public static ProductListCache from(Page<ProductInfo> page) {
        return new ProductListCache(
                page.getContent(),
                page.getTotalElements(),
                page.getSize()
        );
    }

    public Page<ProductInfo> toPage(Pageable pageable) {
        return new PageImpl<>(content, pageable, totalElements);
    }
}
