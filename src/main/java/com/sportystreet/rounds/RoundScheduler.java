package com.sportystreet.rounds;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoundScheduler {
  private final RoundService rounds;
  private final RoundLifecycleService lifecycle;

  public RoundScheduler(RoundService rounds, RoundLifecycleService lifecycle) {
    this.rounds = rounds;
    this.lifecycle = lifecycle;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 5000)
  public void startDue() {
    for (var id : rounds.due())
      try {
        lifecycle.start(id, false);
      } catch (RuntimeException e) {
        LoggerFactory.getLogger(getClass()).warn("Could not start round {}", id, e);
      }
  }
}
