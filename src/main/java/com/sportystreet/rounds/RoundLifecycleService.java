package com.sportystreet.rounds;

import com.sportystreet.teams.TeamService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoundLifecycleService {
  private final RoundService rounds;
  private final TeamService teams;

  public RoundLifecycleService(RoundService rounds, TeamService teams) {
    this.rounds = rounds;
    this.teams = teams;
  }

  @Transactional
  public RoundService.RoundResponse start(UUID id, boolean early) {
    var before = rounds.lock(id);
    if (before.status().equals("LIVE")) return before;
    var after = rounds.start(id, early);
    teams.activate(id, after.entryDeadline());
    return after;
  }
}
