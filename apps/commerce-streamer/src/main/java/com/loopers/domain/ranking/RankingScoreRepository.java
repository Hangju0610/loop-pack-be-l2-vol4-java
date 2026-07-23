package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.Map;

public interface RankingScoreRepository {

    void incrementScores(LocalDate date, Map<String, Double> scoreDeltaByProductId);

    boolean hasRanking(LocalDate date);

    boolean tryMarkCarryOverDone(LocalDate date);

    void carryOver(LocalDate from, LocalDate to, double weight);
}
