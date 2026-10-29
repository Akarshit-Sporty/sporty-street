package com.sportystreet.devtools;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class ShowcaseSchedule {
  private final ShowcaseSeed seed;

  public ShowcaseSchedule(ShowcaseSeed seed) {
    this.seed = seed;
  }

  @Scheduled(cron = "0 10 * * * *", zone = "UTC")
  public void refresh() {
    seed.refreshSchedule();
  }
}
