package com.loopers.domain.like;

/** 좋아요가 취소(soft-delete)되었음을 알리는 도메인 이벤트 (얇은 이벤트). */
public record LikeRemovedEvent(String userId, String productId) {
}
