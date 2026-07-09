package com.loopers.domain.waitingqueue;

import java.util.List;
import java.util.Optional;

public interface WaitingQueueRepository {

    void add(WaitingQueueEntryVO entry);

    Optional<Long> findRank(String userId);

    List<String> popMin(int count);

    long count();
}
