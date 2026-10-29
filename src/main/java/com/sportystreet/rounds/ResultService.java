package com.sportystreet.rounds;

import com.sportystreet.scoring.ScoreService;
import com.sportystreet.shared.ApiException;
import com.sportystreet.teams.TeamService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class ResultService {
  public record Result(
      UUID teamId,
      UUID roundId,
      int finalScore,
      long finalRank,
      int participationRp,
      int winnerRp,
      int totalAwardedRp,
      Instant settledAt,
      ScoreService.Breakdown breakdown) {}

  private final JdbcTemplate db;
  private final TeamService teams;
  private final ScoreService scores;
  private final RoundService rounds;
  private final Clock clock;

  public ResultService(
      JdbcTemplate db, TeamService teams, ScoreService scores, RoundService rounds, Clock clock) {
    this.db = db;
    this.teams = teams;
    this.scores = scores;
    this.rounds = rounds;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void save(
      UUID roundId, List<ScoreService.Entry> standings, int participationRp, int winnerBonusRp) {
    for (var entry : standings)
      db.update(
          "insert into round_result values (?,?,?,?,?,?,?)",
          entry.teamId(),
          roundId,
          entry.score(),
          entry.rank(),
          participationRp,
          entry.rank() == 1 ? winnerBonusRp : 0,
          Timestamp.from(clock.instant()));
  }

  public Result mine(UUID roundId, UUID userId) {
    if (!rounds.get(roundId).status().equals("SETTLED"))
      throw ApiException.rule("ROUND_NOT_SETTLED", "Round is not settled");
    UUID teamId = teams.mine(roundId, userId).id();
    return get(teamId);
  }

  public Result mineOrNull(UUID roundId, UUID userId) {
    if (!rounds.get(roundId).status().equals("SETTLED")) return null;
    var team = teams.mineOrNull(roundId, userId);
    return team == null ? null : get(team.id());
  }

  private Result get(UUID teamId) {
    return db
        .query(
            "select * from round_result where team_id=?",
            (rs, n) ->
                new Result(
                    teamId,
                    rs.getObject("round_id", UUID.class),
                    rs.getInt("final_score"),
                    rs.getLong("final_rank"),
                    rs.getInt("participation_rp"),
                    rs.getInt("winner_rp"),
                    rs.getInt("participation_rp") + rs.getInt("winner_rp"),
                    rs.getTimestamp("settled_at").toInstant(),
                    scores.breakdown(teamId)),
            teamId)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }
}
