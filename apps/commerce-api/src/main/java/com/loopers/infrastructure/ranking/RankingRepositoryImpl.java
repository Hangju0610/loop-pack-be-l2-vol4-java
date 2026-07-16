package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class RankingRepositoryImpl implements RankingRepository {

    private static final String KEY_PREFIX = "ranking:all:";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public List<RankingItem> findPage(LocalDate date, long offset, long count) {
        String key = buildKey(date);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, offset, offset + count - 1);
        if (tuples == null) {
            return List.of();
        }

        List<RankingItem> items = new ArrayList<>();
        long rank = offset + 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            double score = tuple.getScore() != null ? tuple.getScore() : 0.0;
            items.add(new RankingItem(tuple.getValue(), score, rank++));
        }
        return items;
    }

    @Override
    public long countByDate(LocalDate date) {
        Long count = redisTemplate.opsForZSet().zCard(buildKey(date));
        return count != null ? count : 0L;
    }

    @Override
    public Optional<Long> findRank(LocalDate date, String productId) {
        Long zeroBasedRank = redisTemplate.opsForZSet().reverseRank(buildKey(date), productId);
        return Optional.ofNullable(zeroBasedRank).map(rank -> rank + 1);
    }

    private String buildKey(LocalDate date) {
        return KEY_PREFIX + date.format(KEY_DATE_FORMAT);
    }
}
