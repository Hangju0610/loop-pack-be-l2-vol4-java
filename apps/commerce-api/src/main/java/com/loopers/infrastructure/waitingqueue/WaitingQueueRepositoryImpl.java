package com.loopers.infrastructure.waitingqueue;

import com.loopers.domain.waitingqueue.WaitingQueueEntryVO;
import com.loopers.domain.waitingqueue.WaitingQueueRepository;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class WaitingQueueRepositoryImpl implements WaitingQueueRepository {

    private static final String WAITING_QUEUE_KEY = "waiting-queue";

    private final RedisTemplate<String, String> redisTemplate;

    public WaitingQueueRepositoryImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void add(WaitingQueueEntryVO entry) {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            RedisSerializer<String> keySerializer = redisTemplate.getStringSerializer();
            connection.zSetCommands().zAdd(
                    keySerializer.serialize(WAITING_QUEUE_KEY),
                    entry.timestamp(),
                    keySerializer.serialize(entry.userId()),
                    RedisZSetCommands.ZAddArgs.empty().gt()
            );
            return null;
        });
    }

    @Override
    public Optional<Long> findRank(String userId) {
        return Optional.ofNullable(redisTemplate.opsForZSet().rank(WAITING_QUEUE_KEY, userId));
    }

    @Override
    public List<String> popMin(int count) {
        Set<ZSetOperations.TypedTuple<String>> popped = redisTemplate.opsForZSet().popMin(WAITING_QUEUE_KEY, count);
        if (popped == null) {
            return List.of();
        }
        return popped.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .toList();
    }

    @Override
    public long count() {
        Long size = redisTemplate.opsForZSet().zCard(WAITING_QUEUE_KEY);
        return size == null ? 0L : size;
    }
}
