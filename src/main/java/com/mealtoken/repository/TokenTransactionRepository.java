package com.mealtoken.repository;

import com.mealtoken.entity.TokenTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TokenTransactionRepository extends JpaRepository<TokenTransaction, Long> {
    List<TokenTransaction> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
    boolean existsByReferenceNumberAndTransactionType(String ref, String type);
}
