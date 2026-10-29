package com.sportystreet.rounds;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_round")
class RoundEntity {
  @Id UUID id;
  String name;
  String status;

  @Column(name = "entry_deadline")
  Instant entryDeadline;

  @Column(name = "sub_window_opens_at")
  Instant subWindowOpensAt;

  @Column(name = "sub_window_closes_at")
  Instant subWindowClosesAt;

  @JdbcTypeCode(SqlTypes.JSON)
  Rules rules;

  @Column(name = "state_version")
  long stateVersion;

  @Column(name = "settled_at")
  Instant settledAt;

  protected RoundEntity() {}
}
