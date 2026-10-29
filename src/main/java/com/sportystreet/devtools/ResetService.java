package com.sportystreet.devtools;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
public class ResetService {
  private final JdbcTemplate db;
  private final DemoSeed users;
  private final GameSeed game;
  private final RewardSeed rewards;

  public ResetService(JdbcTemplate db, DemoSeed users, GameSeed game, RewardSeed rewards) {
    this.db = db;
    this.users = users;
    this.game = game;
    this.rewards = rewards;
  }

  @Transactional
  public void reset() {
    db.execute(
        "truncate"
            + " reward_ledger,gift_purchase,gift,reward_wallet,round_result,team_substitution,score_contribution,match_event,team_score,team_selection,team,round_player,fixture,player,club,app_round,idempotency_record,app_user,country");
    users.run(null);
    game.run(null);
    rewards.run(null);
  }
}
