package com.loopers.domain.waitingqueue;

import java.util.Optional;

public interface EntryTokenRepository {

    Optional<EntryTokenVO> find(String userId);

    void save(EntryTokenVO token);
}
