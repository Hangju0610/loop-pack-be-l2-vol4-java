package com.loopers.domain.like;

/** 좋아요가 등록(신규 저장 또는 restore)되었음을 알리는 도메인 이벤트 (얇은 이벤트). */
public record LikeAddedEvent(String userId, String productId) {
}
