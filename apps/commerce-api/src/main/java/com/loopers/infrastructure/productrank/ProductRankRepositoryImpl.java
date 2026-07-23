package com.loopers.infrastructure.productrank;

import com.loopers.domain.ranking.ProductRankRepository;
import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductRankRepositoryImpl implements ProductRankRepository {

    private static final Sort SCORE_DESC = Sort.by(Sort.Direction.DESC, "score");

    private final ProductRankWeeklyMvJpaRepository weeklyJpaRepository;
    private final ProductRankMonthlyMvJpaRepository monthlyJpaRepository;

    @Override
    public List<RankingItem> findTopN(RankingPeriod period, LocalDate asOfDate, long limit, long offset) {
        OffsetBasedPageRequest pageable = new OffsetBasedPageRequest(offset, (int) limit, SCORE_DESC);

        List<RankingItem> items = new ArrayList<>();
        long rank = offset + 1;
        switch (period) {
            case WEEKLY -> {
                for (ProductRankWeeklyMvJpaEntity row : weeklyJpaRepository.findById_AsOfDate(asOfDate, pageable)) {
                    items.add(new RankingItem(row.getId().getProductId(), row.getScore(), rank++));
                }
            }
            case MONTHLY -> {
                for (ProductRankMonthlyMvJpaEntity row : monthlyJpaRepository.findById_AsOfDate(asOfDate, pageable)) {
                    items.add(new RankingItem(row.getId().getProductId(), row.getScore(), rank++));
                }
            }
            case DAILY -> throw new IllegalArgumentException("DAILY 기간은 RDB MV 조회 대상이 아닙니다.");
        }
        return items;
    }

    @Override
    public long countByAsOfDate(RankingPeriod period, LocalDate asOfDate) {
        return switch (period) {
            case WEEKLY -> weeklyJpaRepository.countById_AsOfDate(asOfDate);
            case MONTHLY -> monthlyJpaRepository.countById_AsOfDate(asOfDate);
            case DAILY -> throw new IllegalArgumentException("DAILY 기간은 RDB MV 조회 대상이 아닙니다.");
        };
    }
}
