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
    // Retain old purchases and their original names/prices; retire the old catalogue.
    for (int i = 0; i < 3; i++)
      db.update("update gift set active=false where id=?", DemoSeed.id("gift:" + i));
    seed(
        "cap",
        "Sporty Red Cap",
        "Red cotton cap for matchday.",
        "SPORTY_MERCH",
        3000,
        0,
        false,
        "red-cap.png");
    seed(
        "shirt",
        "Sporty Red T-shirt",
        "Red cotton T-shirt for your matchday outfit.",
        "SPORTY_MERCH",
        4500,
        0,
        false,
        "red-shirt.jpg");
    seed(
        "hoodie",
        "Sporty Red Hoodie",
        "Warm red hoodie for evenings at the ground.",
        "SPORTY_MERCH",
        6000,
        0,
        false,
        "red-hoodie.jpg");
    seed(
        "referral",
        "Referral Reward",
        "Free referral gift for every account. Local reward voucher.",
        "SPORTYBET_REWARDS",
        0,
        0,
        true,
        "referral.png");
    seed(
        "free-bet",
        "Free Bet",
        "Unlock with 500 lifetime leaderboard points. Local reward voucher.",
        "SPORTYBET_REWARDS",
        0,
        500,
        true,
        "free-bet.png");
    seed(
        "cashback",
        "Cashback",
        "Unlock with 3,000 lifetime leaderboard points. Local reward voucher.",
        "SPORTYBET_REWARDS",
        0,
        3000,
        true,
        "cashback.png");
    seed(
        "wheel",
        "Lucky Wheel",
        "Unlock with 5,000 lifetime leaderboard points. Local reward voucher.",
        "SPORTYBET_REWARDS",
        0,
        5000,
        true,
        "wheel.png");
  }

  private void seed(
      String key,
      String name,
      String description,
      String category,
      int price,
      int points,
      boolean once,
      String image) {
    db.update(
        """
insert into gift(id,name,description,category,image_url,price_rp,active,required_points,one_per_user)
values(?,?,?,?,?,?,true,?,?) on conflict(id) do update set name=excluded.name,description=excluded.description,
category=excluded.category,image_url=excluded.image_url,price_rp=excluded.price_rp,required_points=excluded.required_points,one_per_user=excluded.one_per_user
""",
        DemoSeed.id("reward:" + key),
        name,
        description,
        category,
        "/media/rewards/" + image,
        price,
        points,
        once);
  }
}
