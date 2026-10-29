package com.sportystreet;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.devtools.*;
import com.sportystreet.ingestion.IngestionService;
import com.sportystreet.live.*;
import com.sportystreet.rounds.*;
import com.sportystreet.scoring.*;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "sporty.showcase.enabled=false")
@ActiveProfiles({"local", "test"})
@AutoConfigureMockMvc
@Import(GameplayIT.TimeConfig.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GameplayIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  static class MutableClock extends Clock {
    volatile Instant now = START;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired ResetService reset;
  @Autowired SimulatorService simulator;
  @Autowired LiveController live;
  @Autowired SnapshotService snapshots;
  @Autowired TeamService teams;
  @Autowired UserService users;
  @Autowired RoundService rounds;
  @Autowired RoundLifecycleService lifecycle;
  @Autowired CatalogueService catalogue;
  @Autowired IngestionService ingestion;
  @Autowired ScoreService scores;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  UUID round = DemoSeed.id("round:1");

  @BeforeEach
  void clean() {
    simulator.reset();
    live.closeAll();
    clock.now = START;
    reset.reset();
    for (int i = 0; i < 3; i++)
      db.update(
          "insert into gift(id,name,description,category,price_rp) values(?, ?, 'Test purchase"
              + " fixture','TEST',?)",
          DemoSeed.id("gift:" + i),
          "Test gift " + i,
          50 * (i + 1));
  }

  @AfterEach
  void stop() {
    simulator.stop();
    live.closeAll();
  }

  UserService.UserResponse alice() {
    return users.byUsername("alice");
  }

  TeamService.Lineup lineup(String user) {
    var pick = teams.autoPick(round, users.byUsername(user), true);
    return new TeamService.Lineup(
        pick.starters().stream().map(CatalogueService.PlayerResponse::id).toList(),
        pick.bench().id(),
        pick.captainPlayerId());
  }

  IngestionService.MatchEventMessage event(
      String key,
      int revision,
      UUID player,
      ScoringEngine.EventType type,
      int minute,
      boolean voided) {
    return new IngestionService.MatchEventMessage(
        "simulator",
        key,
        revision,
        catalogue.player(round, player, null).fixtureId(),
        player,
        type,
        minute,
        START.plusSeconds(1),
        voided);
  }

  @Test
  void coreHttpFlowAndProviderDeduplication() throws Exception {
    var request = lineup("alice");
    String body = json.writeValueAsString(request);
    var created =
        mvc.perform(
                post("/api/v1/rounds/" + round + "/teams")
                    .header("X-Dev-User", "alice")
                    .header("Idempotency-Key", "core")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post("/api/v1/rounds/" + round + "/teams")
                .header("X-Dev-User", "alice")
                .header("Idempotency-Key", "core")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(content().json(created));
    assertThat(teams.mine(round, alice().id()).score()).isZero();
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(180);
    var goal =
        event("late-goal", 1, request.captainPlayerId(), ScoringEngine.EventType.GOAL, 75, false);
    String eventBody = json.writeValueAsString(goal);
    var accepted =
        mvc.perform(
                post("/api/v1/internal/match-events")
                    .header("X-Provider-Token", "local-demo-provider-token")
                    .contentType("application/json")
                    .content(eventBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post("/api/v1/internal/match-events")
                .header("X-Provider-Token", "local-demo-provider-token")
                .contentType("application/json")
                .content(eventBody))
        .andExpect(status().isOk())
        .andExpect(content().json(accepted));
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(22);
    assertThat(db.queryForObject("select count(*) from match_event", Integer.class)).isEqualTo(1);
    var teamId = teams.mine(round, alice().id()).id();
    mvc.perform(get("/api/v1/teams/" + teamId + "/score-breakdown").header("X-Dev-User", "bob"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/rounds/" + round + "/leaderboard").header("X-Dev-User", "alice"))
        .andExpect(status().isOk());
  }

  @Test
  void validatesSquadRulesAndDeadline() {
    var valid = lineup("alice");
    fails(
        "WRONG_SQUAD_SIZE",
        () ->
            teams.create(
                round,
                alice(),
                new TeamService.Lineup(
                    valid.starterPlayerIds().subList(0, 4),
                    valid.benchPlayerId(),
                    valid.captainPlayerId())));
    fails(
        "DUPLICATE_PLAYER",
        () ->
            teams.create(
                round,
                alice(),
                new TeamService.Lineup(
                    valid.starterPlayerIds(), valid.captainPlayerId(), valid.captainPlayerId())));
    fails(
        "CAPTAIN_NOT_STARTER",
        () ->
            teams.create(
                round,
                alice(),
                new TeamService.Lineup(
                    valid.starterPlayerIds(), valid.benchPlayerId(), valid.benchPlayerId())));
    fails(
        "PLAYER_INELIGIBLE",
        () ->
            teams.create(
                round,
                alice(),
                new TeamService.Lineup(
                    valid.starterPlayerIds(), UUID.randomUUID(), valid.captainPlayerId())));
    var starters = new ArrayList<>(valid.starterPlayerIds());
    var country = catalogue.player(round, starters.getFirst(), null).countryId();
    var duplicate =
        catalogue.players(round, null).stream()
            .filter(
                p ->
                    p.countryId().equals(country)
                        && !starters.contains(p.id())
                        && !p.id().equals(valid.benchPlayerId()))
            .findFirst()
            .orElseThrow();
    starters.set(4, duplicate.id());
    fails(
        "DUPLICATE_COUNTRY",
        () ->
            teams.create(
                round,
                alice(),
                new TeamService.Lineup(starters, valid.benchPlayerId(), valid.captainPlayerId())));
    clock.now = rounds.get(round).entryDeadline();
    fails("ROUND_LOCKED", () -> teams.create(round, alice(), valid));
  }

  @Test
  void autoPickWorksForAllSeededUsersAndPrefersHome() {
    for (String name : List.of("alice", "dave", "eve", "frank", "grace", "henry")) {
      var user = users.byUsername(name);
      var pick = teams.autoPick(round, user, true);
      assertThat(
              pick.starters().stream().map(CatalogueService.PlayerResponse::countryId).distinct())
          .hasSize(5);
      if (user.countryId() != null)
        assertThat(pick.starters()).anyMatch(CatalogueService.PlayerResponse::isHomeCountry);
      teams.create(round, user, lineup(name));
    }
    fails("TEAM_ALREADY_EXISTS", () -> teams.create(round, alice(), lineup("alice")));
  }

  @Test
  void correctionsRebuildAndOlderRevisionsDoNotRescore() {
    var lineup = lineup("alice");
    teams.create(round, alice(), lineup);
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(200);
    ingestion.accept(
        event("corrected", 1, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 74, false),
        "simulator");
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(12);
    ingestion.accept(
        event("corrected", 3, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 75, false),
        "simulator");
    long version = rounds.get(round).stateVersion();
    ingestion.accept(
        event("corrected", 2, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 74, false),
        "simulator");
    assertThat(rounds.get(round).stateVersion()).isEqualTo(version);
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(22);
    ingestion.accept(
        event("corrected", 4, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 75, true),
        "simulator");
    assertThat(teams.mine(round, alice().id()).score()).isZero();
    fails(
        "EVENT_REVISION_CONFLICT",
        () ->
            ingestion.accept(
                event(
                    "corrected",
                    4,
                    lineup.captainPlayerId(),
                    ScoringEngine.EventType.GOAL,
                    75,
                    false),
                "simulator"));
  }

  @Test
  void appearanceIsCountedOnceAndNoHomeCountryMeansNoBonus() {
    var user = users.byUsername("grace");
    var lineup = lineup("grace");
    teams.create(round, user, lineup);
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(200);
    ingestion.accept(
        event(
            "appearance1",
            1,
            lineup.captainPlayerId(),
            ScoringEngine.EventType.APPEARANCE,
            0,
            false),
        "simulator");
    ingestion.accept(
        event(
            "appearance2",
            1,
            lineup.captainPlayerId(),
            ScoringEngine.EventType.APPEARANCE,
            0,
            false),
        "simulator");
    assertThat(teams.mine(round, user.id()).score()).isEqualTo(2);
    ingestion.accept(
        event("goal", 1, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 75, false),
        "simulator");
    assertThat(teams.mine(round, user.id()).score()).isEqualTo(22);
  }

  @Test
  void snapshotAndSseContainOnlyOwnersTeamAndReconnectHasCurrentScore() throws Exception {
    var lineup = lineup("alice");
    var own = teams.create(round, alice(), lineup);
    var bob = users.byUsername("bob");
    assertThat(snapshots.get(round, bob.id()).payload().myTeam().id()).isNotEqualTo(own.id());
    var stream =
        mvc.perform(get("/api/v1/rounds/" + round + "/stream").header("X-Dev-User", "alice"))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();
    assertThat(stream.getResponse().getContentAsString())
        .contains("ROUND_SNAPSHOT")
        .contains(own.id().toString());
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(200);
    ingestion.accept(
        event("sse-goal", 1, lineup.captainPlayerId(), ScoringEngine.EventType.GOAL, 75, false),
        "simulator");
    live.publish();
    assertThat(stream.getResponse().getContentAsString()).contains("\"score\":22");
    var reconnect =
        mvc.perform(get("/api/v1/rounds/" + round + "/stream").header("X-Dev-User", "bob"))
            .andExpect(status().isOk())
            .andReturn();
    String data =
        reconnect
            .getResponse()
            .getContentAsString()
            .lines()
            .filter(l -> l.startsWith("data:"))
            .findFirst()
            .orElseThrow()
            .substring(5);
    var payload = json.readTree(data).get("payload");
    assertThat(payload.get("myTeam").get("id").asText())
        .isEqualTo(teams.mine(round, bob.id()).id().toString());
    assertThat(payload.get("myTeam").get("id").asText()).isNotEqualTo(own.id().toString());
  }

  @Autowired SettlementService settlement;
  @Autowired ResultService results;

  @Test
  void editLineupChecksVersionOwnerAndDeadline() {
    var pick = lineup("alice");
    var t = teams.create(round, alice(), pick);
    var update =
        new TeamService.UpdateLineup(
            pick.starterPlayerIds(), pick.benchPlayerId(), pick.starterPlayerIds().get(1), 0L);
    var changed = teams.update(t.id(), alice().id(), update);
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.captainPlayerId()).isEqualTo(pick.starterPlayerIds().get(1));
    fails("VERSION_CONFLICT", () -> teams.update(t.id(), alice().id(), update));
    fails("FORBIDDEN", () -> teams.update(t.id(), users.byUsername("bob").id(), update));
    lifecycle.start(round, true);
    fails(
        "ROUND_LOCKED",
        () ->
            teams.update(
                t.id(),
                alice().id(),
                new TeamService.UpdateLineup(
                    pick.starterPlayerIds(), pick.benchPlayerId(), pick.captainPlayerId(), 1L)));
  }

  @Test
  void substitutionCutoffMovesHomeHeroWithoutTransferringCaptain() {
    var pick = lineup("alice");
    var replacement =
        catalogue.players(round, null).stream()
            .filter(
                p ->
                    p.countryId().equals(alice().countryId())
                        && !pick.starterPlayerIds().contains(p.id()))
            .findFirst()
            .orElseThrow();
    var t =
        teams.create(
            round,
            alice(),
            new TeamService.Lineup(
                pick.starterPlayerIds(), replacement.id(), pick.captainPlayerId()));
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(10);
    var pending =
        teams.substitute(
            t.id(),
            alice().id(),
            new TeamService.Substitute(pick.captainPlayerId(), replacement.id(), 0L));
    assertThat(pending.pendingSubstitution().effectiveAt()).isEqualTo(START.plusSeconds(70));
    assertThat(pending.homeHeroPlayerId()).isEqualTo(pick.captainPlayerId());
    assertThat(pending.subsRemaining()).isZero();
    clock.now = START.plusSeconds(70);
    var active = teams.mine(round, alice().id());
    assertThat(active.pendingSubstitution()).isNull();
    assertThat(active.homeHeroPlayerId()).isEqualTo(replacement.id());
    assertThat(active.version()).isEqualTo(pending.version());
    assertThat(active.stateVersion()).isEqualTo(pending.stateVersion());
    clock.now = START.plusSeconds(100);
    sendAt("out-before", pick.captainPlayerId(), START.plusSeconds(69));
    sendAt("out-at", pick.captainPlayerId(), START.plusSeconds(70));
    sendAt("in-before", replacement.id(), START.plusSeconds(69));
    sendAt("in-at", replacement.id(), START.plusSeconds(70));
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(12 + 7);
    fails(
        "SUBSTITUTION_USED",
        () ->
            teams.substitute(
                t.id(),
                alice().id(),
                new TeamService.Substitute(replacement.id(), pick.captainPlayerId(), 1L)));
  }

  private void sendAt(String key, UUID player, Instant time) {
    var e = event(key, 1, player, ScoringEngine.EventType.GOAL, 20, false);
    ingestion.accept(
        new IngestionService.MatchEventMessage(
            e.provider(),
            e.providerEventId(),
            e.revision(),
            e.fixtureId(),
            e.playerId(),
            e.eventType(),
            e.matchMinute(),
            time,
            false),
        "simulator");
  }

  TeamService.Substitute validSub(TeamService.TeamSnapshot team) {
    var country = team.bench().player().countryId();
    var outgoing =
        team.starters().stream()
            .filter(s -> s.player().countryId().equals(country))
            .findFirst()
            .orElse(team.starters().getFirst());
    return new TeamService.Substitute(
        outgoing.player().id(), team.bench().player().id(), team.version());
  }

  @Test
  void substitutionRejectsInvalidCountryIneligibilityAndClosedWindow() {
    var t = teams.create(round, alice(), lineup("alice"));
    fails("ROUND_NOT_LIVE", () -> teams.substitute(t.id(), alice().id(), validSub(t)));
    lifecycle.start(round, true);
    fails(
        "INVALID_SUBSTITUTION",
        () ->
            teams.substitute(
                t.id(),
                alice().id(),
                new TeamService.Substitute(UUID.randomUUID(), t.bench().player().id(), 0L)));
    db.update(
        "update round_player set eligible=false where round_id=? and player_id=?",
        round,
        t.bench().player().id());
    fails("PLAYER_INELIGIBLE", () -> teams.substitute(t.id(), alice().id(), validSub(t)));
    db.update(
        "update round_player set eligible=true where round_id=? and player_id=?",
        round,
        t.bench().player().id());
    clock.now = rounds.get(round).subWindowClosesAt();
    fails("SUBSTITUTION_WINDOW_CLOSED", () -> teams.substitute(t.id(), alice().id(), validSub(t)));
  }

  @Test
  void substitutionCannotDuplicateCountry() {
    var pick = lineup("alice");
    var bench =
        catalogue.players(round, null).stream()
            .filter(
                p ->
                    p.countryId().equals(alice().countryId())
                        && !pick.starterPlayerIds().contains(p.id()))
            .findFirst()
            .orElseThrow();
    var t =
        teams.create(
            round,
            alice(),
            new TeamService.Lineup(pick.starterPlayerIds(), bench.id(), pick.captainPlayerId()));
    lifecycle.start(round, true);
    fails(
        "DUPLICATE_COUNTRY",
        () ->
            teams.substitute(
                t.id(),
                alice().id(),
                new TeamService.Substitute(pick.starterPlayerIds().get(1), bench.id(), 0L)));
  }

  @Test
  void concurrentSubstitutionsHaveOneWinner() throws Exception {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    var request = validSub(t);
    UUID user = alice().id();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    teams.substitute(t.id(), user, request);
                    return "OK";
                  } catch (ApiException e) {
                    return e.code();
                  }
                }));
      gate.countDown();
      var outcomes = new ArrayList<String>();
      for (var job : jobs) outcomes.add(job.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(outcomes).containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
    }
    assertThat(db.queryForObject("select count(*) from team_substitution", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void settlementIsFinalIdempotentAndRejectsPendingOrRunningSimulator() {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    simulator.start(round);
    fails("ROUND_BUSY", () -> simulator.settle(round));
    simulator.stop();
    teams.substitute(t.id(), alice().id(), validSub(t));
    fails("ROUND_BUSY", () -> settlement.settle(round));
    clock.now = START.plusSeconds(100);
    var original =
        event("settlement-event", 1, t.captainPlayerId(), ScoringEngine.EventType.GOAL, 20, false);
    var acceptance = ingestion.accept(original, "simulator");
    var first = settlement.settle(round);
    assertThat(settlement.settle(round)).isEqualTo(first);
    assertThat(ingestion.accept(original, "simulator")).isEqualTo(acceptance);
    fails(
        "ROUND_SETTLED",
        () ->
            ingestion.accept(
                event(
                    "settlement-event",
                    2,
                    t.captainPlayerId(),
                    ScoringEngine.EventType.GOAL,
                    20,
                    true),
                "simulator"));
    assertThat(results.mine(round, alice().id()).finalScore())
        .isEqualTo(teams.mine(round, alice().id()).score());
    assertThat(snapshots.get(round, alice().id()).payload().result()).isNotNull();
  }

  @Test
  void settlementRacingWithEventProducesConsistentFinalResult() throws Exception {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(10);
    var e = event("racing", 1, t.captainPlayerId(), ScoringEngine.EventType.GOAL, 20, false);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var settle = pool.submit(() -> settlement.settle(round));
      var ingest =
          pool.submit(
              () -> {
                try {
                  ingestion.accept(e, "simulator");
                } catch (ApiException ex) {
                  assertThat(ex.code()).isEqualTo("ROUND_SETTLED");
                }
              });
      settle.get(10, java.util.concurrent.TimeUnit.SECONDS);
      ingest.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(results.mine(round, alice().id()).finalScore())
        .isEqualTo(teams.mine(round, alice().id()).score());
  }

  @Autowired com.sportystreet.rewards.RewardService rewards;
  @Autowired IdempotencyService idempotency;

  void finishAliceWin() {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(10);
    ingestion.accept(
        event("alice-win", 1, t.captainPlayerId(), ScoringEngine.EventType.GOAL, 20, false),
        "simulator");
    settlement.settle(round);
  }

  void assertWalletMatchesLedger(UUID user) {
    int sum =
        db.queryForObject(
            "select coalesce(sum(delta),0) from reward_ledger where user_id=?",
            Integer.class,
            user);
    assertThat(rewards.wallet(user).balanceRp()).isEqualTo(sum).isNotNegative();
  }

  @Test
  void roundAwardsParticipationAndWinnerBonusOnlyOnce() throws Exception {
    finishAliceWin();
    assertThat(rewards.wallet(alice().id()).balanceRp()).isEqualTo(150);
    assertThat(rewards.wallet(users.byUsername("bob").id()).balanceRp()).isEqualTo(50);
    assertThat(results.mine(round, alice().id()).totalAwardedRp()).isEqualTo(150);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a = pool.submit(() -> settlement.settle(round));
      var b = pool.submit(() -> settlement.settle(round));
      assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(rewards.history(alice().id(), 0, 100).total()).isEqualTo(2);
    assertWalletMatchesLedger(alice().id());
  }

  @Test
  void tiedWinnersAndSoloEntrantGetFullBonus() {
    teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    settlement.settle(round);
    for (String name : List.of("alice", "bob", "carol"))
      assertThat(rewards.wallet(users.byUsername(name).id()).balanceRp()).isEqualTo(150);
    UUID other = DemoSeed.id("round:2");
    rounds.open(other);
    var pick = teams.autoPick(other, alice(), true);
    teams.create(
        other,
        alice(),
        new TeamService.Lineup(
            pick.starters().stream().map(CatalogueService.PlayerResponse::id).toList(),
            pick.bench().id(),
            pick.captainPlayerId()));
    lifecycle.start(other, true);
    settlement.settle(other);
    assertThat(rewards.wallet(alice().id()).balanceRp()).isEqualTo(300);
    assertWalletMatchesLedger(alice().id());
  }

  @Test
  void emptyRoundHasNoAwards() {
    UUID other = DemoSeed.id("round:2");
    rounds.open(other);
    lifecycle.start(other, true);
    settlement.settle(other);
    assertThat(db.queryForObject("select count(*) from reward_ledger", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from round_result", Integer.class)).isZero();
  }

  @Test
  void failedSettlementRollsBackCreditsAndFinalState() {
    teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    db.execute(
        "create function reject_result() returns trigger language plpgsql as $$ begin raise"
            + " exception 'injected settlement failure'; end $$");
    db.execute(
        "create trigger test_reject_result before insert on round_result for each row execute"
            + " function reject_result()");
    try {
      assertThatThrownBy(() -> settlement.settle(round)).isInstanceOf(RuntimeException.class);
    } finally {
      db.execute("drop trigger test_reject_result on round_result");
      db.execute("drop function reject_result()");
    }
    assertThat(rounds.get(round).status()).isEqualTo("LIVE");
    assertThat(db.queryForObject("select count(*) from reward_ledger", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from round_result", Integer.class)).isZero();
    assertThat(rewards.wallet(alice().id()).balanceRp()).isZero();
    settlement.settle(round);
    assertThat(rewards.wallet(alice().id()).balanceRp()).isEqualTo(150);
  }

  @Test
  void purchaseIsIdempotentAndOnlyOwnerSeesVoucher() throws Exception {
    finishAliceWin();
    String path = "/api/v1/gifts/" + DemoSeed.id("gift:0") + "/purchases";
    String first =
        mvc.perform(post(path).header("X-Dev-User", "alice").header("Idempotency-Key", "gift-once"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.balanceRp").value(100))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(post(path).header("X-Dev-User", "alice").header("Idempotency-Key", "gift-once"))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    mvc.perform(get("/api/v1/me/purchases").header("X-Dev-User", "bob"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    assertThat(rewards.purchases(alice().id(), 0, 100).total()).isEqualTo(1);
    assertWalletMatchesLedger(alice().id());
    fails("INSUFFICIENT_RP", () -> rewards.purchase(alice().id(), DemoSeed.id("gift:2")));
    db.update("update gift set active=false where id=?", DemoSeed.id("gift:0"));
    fails("GIFT_UNAVAILABLE", () -> rewards.purchase(alice().id(), DemoSeed.id("gift:0")));
  }

  @Test
  void concurrentPurchasesCannotOverspend() throws Exception {
    finishAliceWin();
    UUID user = alice().id();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    rewards.purchase(user, DemoSeed.id("gift:1"));
                    return "OK";
                  } catch (ApiException e) {
                    return e.code();
                  }
                }));
      gate.countDown();
      var outcomes = new ArrayList<String>();
      for (var job : jobs) outcomes.add(job.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(outcomes.stream().filter("OK"::equals)).hasSize(1);
      assertThat(outcomes.stream().filter("INSUFFICIENT_RP"::equals)).hasSize(3);
    }
    assertThat(rewards.wallet(user).balanceRp()).isEqualTo(50);
    assertWalletMatchesLedger(user);
  }

  @Test
  void concurrentSameKeyPurchaseCreatesOneDebitAndVoucher() throws Exception {
    finishAliceWin();
    UUID user = alice().id(), gift = DemoSeed.id("gift:0");
    String key = UUID.randomUUID().toString();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<java.util.concurrent.Future<IdempotencyService.StoredResponse>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            pool.submit(
                () ->
                    idempotency.execute(
                        user,
                        key,
                        "POST /gifts/" + gift + "/purchases",
                        Map.of(),
                        201,
                        () -> rewards.purchase(user, gift))));
      var first = jobs.getFirst().get(10, java.util.concurrent.TimeUnit.SECONDS);
      for (var job : jobs)
        assertThat(job.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
    }
    assertThat(rewards.purchases(user, 0, 100).total()).isEqualTo(1);
    assertThat(rewards.wallet(user).balanceRp()).isEqualTo(100);
    assertWalletMatchesLedger(user);
  }

  @Test
  void failedPurchaseRollsBackDebitAndIdempotencyKey() {
    finishAliceWin();
    UUID user = alice().id(), gift = DemoSeed.id("gift:0");
    String key = "retry-failure";
    db.execute(
        "create function reject_purchase() returns trigger language plpgsql as $$ begin raise"
            + " exception 'injected purchase failure'; end $$");
    db.execute(
        "create trigger test_reject_purchase before insert on gift_purchase for each row execute"
            + " function reject_purchase()");
    try {
      assertThatThrownBy(
              () ->
                  idempotency.execute(
                      user,
                      key,
                      "POST /purchase-test",
                      Map.of(),
                      201,
                      () -> rewards.purchase(user, gift)))
          .isInstanceOf(RuntimeException.class);
    } finally {
      db.execute("drop trigger test_reject_purchase on gift_purchase");
      db.execute("drop function reject_purchase()");
    }
    assertThat(rewards.wallet(user).balanceRp()).isEqualTo(150);
    assertThat(rewards.purchases(user, 0, 100).total()).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from idempotency_record where key=?", Integer.class, key))
        .isZero();
    idempotency.execute(
        user, key, "POST /purchase-test", Map.of(), 201, () -> rewards.purchase(user, gift));
    assertWalletMatchesLedger(user);
  }

  @Test
  void fullSimulatorCompletesAndReplayDoesNotChangeScore() {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(10);
    teams.substitute(t.id(), alice().id(), validSub(t));
    simulator.start(round);
    clock.now = START.plusSeconds(200);
    for (int i = 0; i < 100 && simulator.status().running(); i++) simulator.tick();
    assertThat(simulator.status().lastError()).isNull();
    assertThat(simulator.status().completed()).isTrue();
    var before = teams.mine(round, alice().id());
    assertThat(before.score()).isPositive();
    assertThat(before.pendingSubstitution()).isNull();
    assertThat(scores.breakdown(t.id()).timeline())
        .anyMatch(l -> l.component().equals("LATE_GOAL_BONUS"));
    assertThat(db.queryForObject("select count(*) from match_event where voided", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from match_event where provider_event_id like '%cutoff:%'",
                Integer.class))
        .isEqualTo(2);
    simulator.start(round);
    for (int i = 0; i < 100 && simulator.status().running(); i++) simulator.tick();
    assertThat(simulator.status().completed()).isTrue();
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(before.score());
    assertThat(rounds.get(round).stateVersion()).isEqualTo(before.stateVersion());
    simulator.settle(round);
    assertThat(results.mine(round, alice().id()).totalAwardedRp()).isGreaterThanOrEqualTo(50);
  }

  @Test
  void concurrentFirstSettlementCreditsEachParticipantOnce() throws Exception {
    teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return settlement.settle(round);
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return settlement.settle(round);
              });
      gate.countDown();
      assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(db.queryForObject("select count(*) from round_result", Integer.class)).isEqualTo(3);
    assertThat(db.queryForObject("select count(*) from reward_ledger", Integer.class)).isEqualTo(6);
    assertThat(rewards.wallet(alice().id()).balanceRp()).isEqualTo(150);
    assertWalletMatchesLedger(alice().id());
  }

  @Test
  void substitutionHttpRetryReturnsOriginalResponseAfterCutoff() throws Exception {
    var t = teams.create(round, alice(), lineup("alice"));
    lifecycle.start(round, true);
    String path = "/api/v1/teams/" + t.id() + "/substitutions",
        body = json.writeValueAsString(validSub(t));
    var first =
        mvc.perform(
                post(path)
                    .header("X-Dev-User", "alice")
                    .header("Idempotency-Key", "swap-once")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    clock.now = START.plusSeconds(65);
    mvc.perform(
            post(path)
                .header("X-Dev-User", "alice")
                .header("Idempotency-Key", "swap-once")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().json(first));
    assertThat(teams.mine(round, alice().id()).pendingSubstitution()).isNull();
    assertThat(db.queryForObject("select count(*) from team_substitution", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void correctingPlayerRemovesOldPlayersPoints() {
    var original = lineup("alice");
    var fixture = catalogue.player(round, original.captainPlayerId(), null).fixtureId();
    var bench =
        db
            .queryForList(
                "select player_id from round_player where round_id=? and fixture_id=? order by"
                    + " player_id",
                UUID.class,
                round,
                fixture)
            .stream()
            .filter(id -> !original.starterPlayerIds().contains(id))
            .findFirst()
            .orElseThrow();
    var pick =
        new TeamService.Lineup(original.starterPlayerIds(), bench, original.captainPlayerId());
    var t = teams.create(round, alice(), pick);
    lifecycle.start(round, true);
    clock.now = START.plusSeconds(10);
    assertThat(catalogue.player(round, pick.benchPlayerId(), null).fixtureId())
        .isEqualTo(catalogue.player(round, pick.captainPlayerId(), null).fixtureId());
    ingestion.accept(
        event("wrong-player", 1, pick.captainPlayerId(), ScoringEngine.EventType.GOAL, 20, false),
        "simulator");
    assertThat(teams.mine(round, alice().id()).score()).isEqualTo(12);
    ingestion.accept(
        event("wrong-player", 2, pick.benchPlayerId(), ScoringEngine.EventType.GOAL, 20, false),
        "simulator");
    assertThat(teams.mine(round, alice().id()).score()).isZero();
    assertThat(scores.breakdown(t.id()).timeline()).isEmpty();
  }

  private void fails(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
