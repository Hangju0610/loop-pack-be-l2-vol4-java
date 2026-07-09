package com.loopers.infrastructure.waitingqueue;

import com.loopers.domain.waitingqueue.EntryTokenRepository;
import com.loopers.domain.waitingqueue.EntryTokenVO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
public class EntryTokenRepositoryImpl implements EntryTokenRepository {

    private static final String KEY_PREFIX = "entry-token:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, String> redisTemplate;

    public EntryTokenRepositoryImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<EntryTokenVO> find(String userId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(new EntryTokenVO(userId, value));
    }

    @Override
    public void save(EntryTokenVO token) {
        redisTemplate.opsForValue().set(KEY_PREFIX + token.userId(), token.token(), TTL);
    }
}
