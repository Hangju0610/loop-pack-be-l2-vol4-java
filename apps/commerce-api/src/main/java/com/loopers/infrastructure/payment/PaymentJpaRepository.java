package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, String> {
    Optional<PaymentJpaEntity> findByTransactionKey(String transactionKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.transactionKey = :transactionKey")
    Optional<PaymentJpaEntity> findByTransactionKeyWithLock(@Param("transactionKey") String transactionKey);

    boolean existsByOrderIdAndStatusIn(String orderId, PaymentStatus... statuses);
}
