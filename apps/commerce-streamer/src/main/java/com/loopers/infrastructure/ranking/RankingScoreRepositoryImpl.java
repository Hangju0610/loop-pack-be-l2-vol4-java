package com.loopers.infrastructure.ranking;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class RankingScoreRepositoryImpl implements RankingScoreRepository {

    private static final String KEY_PREFIX = "ranking:all:";
    private static final String CARRY_OVER_KEY_PREFIX = "ranking:carryover:";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration TTL = Duration.ofDays(2);

    private final RedisTemplate<String, String> redisTemplate;

    public RankingScoreRepositoryImpl(
            @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void incrementScores(LocalDate date, Map<String, Double> scoreDeltaByProductId) {
        if (scoreDeltaByProductId.isEmpty()) {
            return;
        }

        String key = buildKey(date);
        scoreDeltaByProductId.forEach((productId, delta) ->
                redisTemplate.opsForZSet().incrementScore(key, productId, delta));
        expireIfNoTtl(key);
    }

    @Override
    public boolean hasRanking(LocalDate date) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(date)));
    }

    @Override
    public boolean tryMarkCarryOverDone(LocalDate date) {
        String guardKey = CARRY_OVER_KEY_PREFIX + date.format(KEY_DATE_FORMAT);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(guardKey, "done", TTL));
    }

    @Override
    public void carryOver(LocalDate from, LocalDate to, double weight) {
        String toKey = buildKey(to);
        redisTemplate.opsForZSet().unionAndStore(
                toKey,
                List.of(buildKey(from)),
                toKey,
                Aggregate.SUM,
                Weights.of(1.0, weight)
        );
        redisTemplate.expire(toKey, TTL);
    }

    private void expireIfNoTtl(String key) {
        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        byte[] seconds = String.valueOf(TTL.toSeconds()).getBytes(StandardCharsets.UTF_8);
        byte[] nx = "NX".getBytes(StandardCharsets.UTF_8);
        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("EXPIRE", rawKey, seconds, nx));
    }

    private String buildKey(LocalDate date) {
        return KEY_PREFIX + date.format(KEY_DATE_FORMAT);
    }
}
