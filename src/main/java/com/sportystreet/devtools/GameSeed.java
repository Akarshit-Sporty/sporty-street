package com.sportystreet.devtools;

import com.sportystreet.rounds.Rules;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("local")
@Order(2)
public class GameSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final Clock clock;
  private final ObjectMapper json;
  private final TeamService teams;
  private final UserService users;
  private final FootballCatalogue catalogue;

  public GameSeed(
      JdbcTemplate db,
      Clock clock,
      ObjectMapper json,
      TeamService teams,
      UserService users,
      FootballCatalogue catalogue) {
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.teams = teams;
    this.users = users;
    this.catalogue = catalogue;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.queryForObject("select count(*) from app_round", Integer.class) > 0) return;
    var start = clock.instant().plusSeconds(600);
    for (int r = 1; r <= 2; r++)
      db.update(
          "insert into app_round values (?,?,?,?,?,?,?::jsonb,0,null)",
          DemoSeed.id("round:" + r),
          r == 1 ? "European Superstars" : "Champions Night",
          r == 1 ? "OPEN" : "DRAFT",
          Timestamp.from(start),
          Timestamp.from(start),
          Timestamp.from(start.plusSeconds(21600)),
          json.writeValueAsString(Rules.defaults()));
    for (int c = 0; c < 6; c++) {
      UUID club = DemoSeed.id("club:" + c);
      var clubData = catalogue.data().clubs().get(c);
      db.update(
          "insert into club values (?,?,?)",
          club,
          clubData.name(),
          DemoSeed.id("country:" + clubData.countryCode()));
      for (int p = 0; p < clubData.players().size(); p++) {
        var player = clubData.players().get(p);
        db.update(
            "insert into player values (?,?,?,?,?,?)",
            DemoSeed.id("player:" + c + ":" + p),
            player.name(),
            player.position(),
            DemoSeed.id("country:" + player.countryCode()),
            club,
            player.imageUrl());
      }
    }
    for (int r = 1; r <= 2; r++)
      for (int f = 0; f < 3; f++) {
        UUID roundId = DemoSeed.id("round:" + r), fixture = DemoSeed.id("fixture:" + r + ":" + f);
        db.update(
            "insert into fixture values (?,?,?,?,?,?,?)",
            fixture,
            roundId,
            "simulator",
            "round-" + r + "-fixture-" + f,
            DemoSeed.id("club:" + (f * 2)),
            DemoSeed.id("club:" + (f * 2 + 1)),
            Timestamp.from(start));
        for (int c = f * 2; c < f * 2 + 2; c++)
          for (int p = 0; p < 10; p++)
            db.update(
                "insert into round_player values (?,?,?,true)",
                roundId,
                DemoSeed.id("player:" + c + ":" + p),
                fixture);
      }
    for (String username : new String[] {"bob", "carol"}) {
      var user = users.byUsername(username);
      var roundId = DemoSeed.id("round:1");
      var pick = teams.autoPick(roundId, user, true);
      teams.create(
          roundId,
          user,
          new TeamService.Lineup(
              pick.starters().stream().map(p -> p.id()).toList(),
              pick.bench().id(),
              pick.captainPlayerId()));
    }
  }
}
