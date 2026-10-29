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
  private final ShowcaseSeed showcase;

  public ResetService(
      JdbcTemplate db, DemoSeed users, GameSeed game, RewardSeed rewards, ShowcaseSeed showcase) {
    this.db = db;
    this.users = users;
    this.game = game;
    this.rewards = rewards;
    this.showcase = showcase;
  }

  @Transactional
  public void reset() {
    db.queryForList("select pg_advisory_xact_lock(714034012)");
    db.execute(
        "truncate"
            + " seed_marker,round_presentation,friendship,round_invitation,reward_ledger,gift_purchase,gift,reward_wallet,round_result,team_substitution,score_contribution,match_event,team_score,team_selection,team,round_player,fixture,player,club,app_round,idempotency_record,app_user,country");
    users.run(null);
    game.run(null);
    rewards.run(null);
    showcase.run(null);
  }
}
