package com.loopers.domain.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshotItem(
        String productId,
        Integer quantity
) {
}
