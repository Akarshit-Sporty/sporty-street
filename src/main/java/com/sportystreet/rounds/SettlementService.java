package com.sportystreet.rounds;

import com.sportystreet.scoring.ScoreService;
import com.sportystreet.shared.ApiException;
import com.sportystreet.teams.TeamService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
  public record Summary(UUID roundId, String status, int participants, long stateVersion) {}

  private final RoundService rounds;
  private final TeamService teams;
  private final ScoreService scores;
  private final ResultService results;
  private final com.sportystreet.rewards.RewardService rewards;

  public SettlementService(
      RoundService rounds,
      TeamService teams,
      ScoreService scores,
      ResultService results,
      com.sportystreet.rewards.RewardService rewards) {
    this.rounds = rounds;
    this.teams = teams;
    this.scores = scores;
    this.results = results;
    this.rewards = rewards;
  }

  @Transactional
  public Summary settle(UUID id) {
    var round = rounds.lock(id);
    var standings = scores.standings(id);
    if (round.status().equals("SETTLED"))
      return new Summary(id, "SETTLED", standings.size(), round.stateVersion());
    if (!round.status().equals("LIVE"))
      throw ApiException.rule("ROUND_NOT_LIVE", "Only live rounds can settle");
    if (teams.hasPendingSubstitution(id))
      throw new ApiException(409, "ROUND_BUSY", "Wait for pending substitutions to activate");
    if (!round.demoMode())
      rewards.award(id, round.rules().rewards(), teams.participants(id), standings);
    results.save(
        id,
        standings,
        round.demoMode() ? 0 : round.rules().rewards().participationRp(),
        round.demoMode() ? 0 : round.rules().rewards().winnerBonusRp());
    rounds.markSettled(id);
    return new Summary(id, "SETTLED", standings.size(), rounds.get(id).stateVersion());
  }
}
