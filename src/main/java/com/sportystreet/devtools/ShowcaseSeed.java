package com.sportystreet.devtools;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.ingestion.IngestionService;
import com.sportystreet.rewards.RewardService;
import com.sportystreet.rounds.*;
import com.sportystreet.scoring.*;
import com.sportystreet.shared.IdempotencyService;
import com.sportystreet.teams.TeamService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Additive, local-only sample activity. Uses the same scoring and reward accounting as live play.
 */
@Component
@Profile("local")
@Order(4)
public class ShowcaseSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final Clock clock;
  private final ObjectMapper json;
  private final CatalogueService catalogue;
  private final TeamService teams;
  private final ScoreService scores;
  private final RewardService rewards;
  private final boolean enabled;

  public ShowcaseSeed(
      JdbcTemplate db,
      Clock clock,
      ObjectMapper json,
      CatalogueService catalogue,
      TeamService teams,
      ScoreService scores,
      RewardService rewards,
      @Value("${sporty.showcase.enabled:true}") boolean enabled) {
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.catalogue = catalogue;
    this.teams = teams;
    this.scores = scores;
    this.rewards = rewards;
    this.enabled = enabled;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    db.queryForList("select pg_advisory_xact_lock(714034012)");
    if (db.queryForObject(
            "select count(*) from seed_marker where name='showcase-history-v1'", Long.class)
        == 0) {
      for (int day = 40; day >= 1; day--) {
        var date = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(day);
        UUID id = DemoSeed.id("history:" + day);
        var start = date.atTime(18, 0).toInstant(ZoneOffset.UTC);
        createRound(id, "European Matchday · " + date, "LIVE", start, 3, "MATCHDAY");
        addTeams(id, start, true);
        historicalEvents(id, start, day);
        var standings = scores.standings(id);
        rewards.award(id, Rules.defaults().rewards(), teams.participants(id), standings);
        var ended = Timestamp.from(start.plusSeconds(7200));
        for (var e : standings)
          db.update(
              "insert into round_result values(?,?,?,?,50,?,?)",
              e.teamId(),
              id,
              e.score(),
              e.rank(),
              e.rank() == 1 ? 100 : 0,
              ended);
        db.update("update reward_ledger set created_at=? where round_id=?", ended, id);
        db.update(
            "update app_round set status='SETTLED',settled_at=?,state_version=2 where id=?",
            ended,
            id);
      }
      for (String name : List.of("bob", "carol", "dave")) {
        var alice = DemoSeed.id("user:alice");
        var friend = DemoSeed.id("user:" + name);
        db.update(
            "insert into friendship values(?,?),(?,?) on conflict do nothing",
            alice,
            friend,
            friend,
            alice);
      }
      db.update("insert into seed_marker(name) values('showcase-history-v1')");
    }
    refreshSchedule();
  }

  @Transactional
  public void refreshSchedule() {
    if (!enabled) return;
    if (db.queryForObject(
            "select count(*) from seed_marker where name='showcase-history-v1'", Long.class)
        == 0) return;
    db.queryForList("select pg_advisory_xact_lock(714034012)");
    var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    String[] names = {
      "European Superstars",
      "Champions Night",
      "El Clásico",
      "Premier League Spotlight",
      "Paris Matchday",
      "Bayern Challenge",
      "Weekend World Five"
    };
    for (int day = 0; day < 7; day++)
      for (int slot = 0; slot < (day < 2 ? 3 : 1); slot++) {
        var date = today.plusDays(day);
        var id = DemoSeed.id("schedule:" + date + ":" + slot);
        if (db.queryForObject("select count(*) from app_round where id=?", Long.class, id) > 0)
          continue;
        var start = date.atTime(18 + slot, 0).toInstant(ZoneOffset.UTC);
        if (day == 0 && !start.isAfter(clock.instant().plusSeconds(600)))
          start = clock.instant().plusSeconds(1800L + slot * 1200L);
        boolean quick = slot == 2;
        int index = (day + slot) % names.length;
        createRound(
            id,
            quick ? "El Clásico" : names[index],
            "OPEN",
            start,
            quick ? 1 : 3,
            quick ? "QUICK" : slot == 1 ? "SPECIAL" : "MATCHDAY");
        addTeams(id, start, false);
      }
    // Make the original draft visible once, without rewriting a live or completed game.
    UUID original = DemoSeed.id("round:2");
    var start = today.plusDays(1).atTime(21, 0).toInstant(ZoneOffset.UTC);
    if (db.update(
            "update app_round set"
                + " status='OPEN',entry_deadline=?,sub_window_opens_at=?,sub_window_closes_at=?"
                + " where id=? and status='DRAFT'",
            Timestamp.from(start),
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(21600)),
            original)
        > 0) {
      db.update(
          "update fixture set kickoff_at=? where round_id=?", Timestamp.from(start), original);
      addTeams(original, start, false);
    }
  }

  private void createRound(
      UUID id, String name, String status, Instant start, int matches, String format) {
    db.update(
        "insert into app_round values(?,?,?,?,?,?,?::jsonb,0,null)",
        id,
        name,
        status,
        Timestamp.from(start),
        Timestamp.from(start),
        Timestamp.from(start.plusSeconds(21600)),
        json.writeValueAsString(Rules.defaults()));
    db.update("insert into round_presentation values(?,?,true)", id, format);
    for (int f = 0; f < matches; f++) {
      UUID fixture = DemoSeed.id("fixture:" + id + ":" + f);
      db.update(
          "insert into fixture values(?,?,'simulator',?,?,?,?)",
          fixture,
          id,
          "showcase-" + id + "-" + f,
          DemoSeed.id("club:" + f * 2),
          DemoSeed.id("club:" + (f * 2 + 1)),
          Timestamp.from(start.plusSeconds(f * 900L)));
      for (int c = f * 2; c < f * 2 + 2; c++)
        for (int p = 0; p < 10; p++)
          db.update(
              "insert into round_player values(?,?,?,true)",
              id,
              DemoSeed.id("player:" + c + ":" + p),
              fixture);
    }
  }

  private void addTeams(UUID round, Instant start, boolean history) {
    var pool = new ArrayList<>(catalogue.players(round, DemoSeed.id("country:BRA")));
    pool.sort(
        Comparator.comparing((CatalogueService.PlayerResponse p) -> !p.isHomeCountry())
            .thenComparing(p -> p.name()));
    var countries = new HashSet<UUID>();
    var starters = new ArrayList<UUID>();
    for (var p : pool)
      if (starters.size() < 5 && countries.add(p.countryId())) starters.add(p.id());
    UUID bench =
        pool.stream().filter(p -> !starters.contains(p.id())).findFirst().orElseThrow().id();
    if (starters.size() != 5) throw new IllegalStateException("Seed round needs five countries");
    var users = db.queryForList("select id,username,country_id from app_user order by username");
    for (int i = 0; i < users.size(); i++) {
      var u = users.get(i);
      String name = (String) u.get("username");
      if (!history && (name.equals("alice") || name.equals("dave"))) continue;
      UUID team = DemoSeed.id("team:" + round + ":" + name);
      db.update(
          "insert into team values(?,?,?,?,?,0,0)",
          team,
          u.get("id"),
          round,
          starters.get(i % 5),
          u.get("country_id"));
      for (var p : starters)
        db.update(
            "insert into team_selection values(?,?,?,'STARTER',?,null)",
            UUID.randomUUID(),
            team,
            p,
            Timestamp.from(start));
      db.update(
          "insert into team_selection values(?,?,?,'BENCH',?,null)",
          UUID.randomUUID(),
          team,
          bench,
          Timestamp.from(start));
      scores.initialize(round, team);
    }
  }

  private void historicalEvents(UUID round, Instant start, int day) {
    var starterIds =
        db.queryForList(
            "select player_id from team_selection where team_id=? and role='STARTER' order by"
                + " player_id",
            UUID.class,
            DemoSeed.id("team:" + round + ":alice"));
    UUID scorer =
        db.queryForObject(
            "select captain_player_id from team where id=?",
            UUID.class,
            DemoSeed.id("team:" + round + ":" + (day % 2 == 0 ? "alice" : "bob")));
    var events = new ArrayList<ScoringEngine.Event>();
    int n = 0;
    for (UUID player : starterIds)
      events.add(event(round, player, ScoringEngine.EventType.APPEARANCE, 1, start, ++n));
    for (int minute : List.of(23, 58, 81))
      events.add(event(round, scorer, ScoringEngine.EventType.GOAL, minute, start, ++n));
    scores.recalculate(round, Rules.defaults(), events, teams.scoringContexts(round));
  }

  private ScoringEngine.Event event(
      UUID round, UUID player, ScoringEngine.EventType type, int minute, Instant start, int n) {
    UUID fixture = catalogue.player(round, player, null).fixtureId(),
        id = DemoSeed.id("event:" + round + ":" + n);
    String providerId = "history-" + round + "-" + n;
    Instant occurred = start.plusSeconds(minute * 60L);
    var request =
        new IngestionService.MatchEventMessage(
            "simulator", providerId, 1, fixture, player, type, minute, occurred, false);
    db.update(
        "insert into match_event values(?,'simulator',?,1,?,?,?,?,?,false,?,?,?)",
        id,
        providerId,
        fixture,
        player,
        type.name(),
        minute,
        Timestamp.from(occurred),
        IdempotencyService.hash(json.writeValueAsString(request)),
        Timestamp.from(occurred),
        json.writeValueAsString(new IngestionService.Acceptance(id, true, 1)));
    return new ScoringEngine.Event(id, fixture, player, type, minute, occurred);
  }
}
