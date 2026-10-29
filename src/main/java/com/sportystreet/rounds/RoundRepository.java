package com.sportystreet.rounds;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

interface RoundRepository extends JpaRepository<RoundEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoundEntity r where r.id=:id")
  Optional<RoundEntity> lock(UUID id);

  List<RoundEntity> findByStatusAndEntryDeadlineLessThanEqual(String status, Instant deadline);
}
