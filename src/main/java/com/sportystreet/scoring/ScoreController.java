package com.sportystreet.scoring;

import com.sportystreet.rounds.RoundService;
import com.sportystreet.shared.PageResponse;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
public class ScoreController {
  public record Leaderboard(
      UUID roundId, long stateVersion, PageResponse<ScoreService.Entry> standings) {}

  private final ScoreService scores;
  private final TeamService teams;
  private final RoundService rounds;

  public ScoreController(ScoreService scores, TeamService teams, RoundService rounds) {
    this.scores = scores;
    this.teams = teams;
    this.rounds = rounds;
  }

  @GetMapping("/teams/{id}/score-breakdown")
  public ScoreService.Breakdown breakdown(
      @PathVariable UUID id, @AuthenticationPrincipal UserService.UserResponse user) {
    teams.requireOwner(id, user.id());
    return scores.breakdown(id);
  }

  @GetMapping("/rounds/{id}/leaderboard")
  public Leaderboard leaderboard(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new Leaderboard(
        id, rounds.get(id).stateVersion(), PageResponse.of(scores.standings(id), page, size));
  }
}
