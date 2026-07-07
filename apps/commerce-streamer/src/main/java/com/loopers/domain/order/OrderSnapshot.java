package com.loopers.domain.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshot(
        List<OrderSnapshotItem> items
) {
}
