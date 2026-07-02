package com.loopers.infrastructure.handled;

import com.loopers.domain.handled.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class EventHandledRepositoryImpl implements EventHandledRepository {

    private final EventHandledJpaRepository jpaRepository;

    @Override
    public boolean markIfNotHandled(String outboxEventId, String consumerGroup) {
        return jpaRepository.insertIgnore(outboxEventId, consumerGroup) == 1;
    }
}
