package com.loopers.interfaces.api.ranking;

import com.loopers.application.product.RankingInfo;

public class RankingV1Dto {

    public record RankingItemResponse(
        long rank,
        String id,
        String brandId,
        String brandName,
        String name,
        Long price,
        Long likeCount
    ) {
        public static RankingItemResponse from(RankingInfo info) {
            return new RankingItemResponse(
                info.rank(),
                info.product().id(),
                info.product().brandId(),
                info.product().brandName(),
                info.product().name(),
                info.product().price(),
                info.product().likeCount()
            );
        }
    }
}
