package com.sportystreet.demo;

import java.time.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local & !test")
@Order(5)
public class DemoLoopWorker implements ApplicationRunner {
  private final DemoLoopService loop;
  private final Clock clock;
  private volatile boolean ready;
  private Instant lastCleanup = Instant.EPOCH;

  public DemoLoopWorker(DemoLoopService loop, Clock clock) {
    this.loop = loop;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    ready = true;
    tick();
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 10000)
  public synchronized void tick() {
    if (!ready) return;
    try {
      loop.cycle();
      if (!clock.instant().isBefore(lastCleanup.plusSeconds(60))) {
        loop.cleanup();
        lastCleanup = clock.instant();
      }
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(getClass())
          .error("Continuous demo tick failed; will retry without advancing its saved cursor", e);
    }
  }
}
