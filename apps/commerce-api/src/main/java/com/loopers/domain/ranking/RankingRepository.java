package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RankingRepository {
    List<RankingItem> findPage(LocalDate date, long offset, long count);
    long countByDate(LocalDate date);
    Optional<Long> findRank(LocalDate date, String productId);
}
