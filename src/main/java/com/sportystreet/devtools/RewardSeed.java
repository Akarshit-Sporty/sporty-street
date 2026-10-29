package com.sportystreet.devtools;

import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@Order(3)
public class RewardSeed implements ApplicationRunner {
  private final JdbcTemplate db;

  public RewardSeed(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    db.update(
        "insert into reward_wallet (user_id,balance) select id,0 from app_user on conflict do"
            + " nothing");
    String[] names = {"Matchday Coffee", "Football Store Discount", "Club Scarf"};
    String[] descriptions = {
      "One coffee to enjoy on matchday.",
      "15% off your next football merchandise purchase.",
      "A scarf in your favourite club's colours."
    };
    String[] categories = {"FOOD", "SHOPPING", "MERCH"};
    for (int i = 0; i < 3; i++)
      db.update(
          "insert into gift values (?,?,?,?,null,?,true) on conflict do nothing",
          DemoSeed.id("gift:" + i),
          names[i],
          descriptions[i],
          categories[i],
          50 * (i + 1));
  }
}
