package com.sportystreet.live;

import com.sportystreet.rounds.RoundService;
import com.sportystreet.scoring.ScoreService;
import com.sportystreet.teams.TeamService;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SnapshotService {
  public record Payload(
      RoundService.RoundResponse round,
      TeamService.TeamSnapshot myTeam,
      List<ScoreService.Entry> leaderboard,
      com.sportystreet.rounds.ResultService.Result result) {}

  public record Envelope(
      String eventType, UUID roundId, long stateVersion, Instant asOf, Payload payload) {}

  private final RoundService rounds;
  private final TeamService teams;
  private final ScoreService scores;
  private final com.sportystreet.rounds.ResultService results;

  public SnapshotService(
      RoundService rounds,
      TeamService teams,
      ScoreService scores,
      com.sportystreet.rounds.ResultService results) {
    this.rounds = rounds;
    this.teams = teams;
    this.scores = scores;
    this.results = results;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Envelope get(UUID roundId, UUID userId) {
    var round = rounds.get(roundId);
    return new Envelope(
        "ROUND_SNAPSHOT",
        roundId,
        round.stateVersion(),
        round.serverTime(),
        new Payload(
            round,
            teams.mineOrNull(roundId, userId),
            scores.standings(roundId).stream().limit(100).toList(),
            results.mineOrNull(roundId, userId)));
  }
}
