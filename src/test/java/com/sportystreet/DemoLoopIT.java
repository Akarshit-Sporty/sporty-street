package com.sportystreet;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.demo.DemoLoopService;
import com.sportystreet.devtools.*;
import com.sportystreet.live.*;
import com.sportystreet.profile.*;
import com.sportystreet.rewards.RewardService;
import com.sportystreet.rounds.*;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"sporty.showcase.enabled=false", "sporty.demo.enabled=true"})
@ActiveProfiles({"local", "test"})
@AutoConfigureMockMvc
@Import(GameplayIT.TimeConfig.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DemoLoopIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired GameplayIT.MutableClock clock;
  @Autowired DemoLoopService loop;
  @Autowired ResetService reset;
  @Autowired RoundService rounds;
  @Autowired RoundLifecycleService lifecycle;
  @Autowired SettlementService settlement;
  @Autowired ResultService results;
  @Autowired TeamService teams;
  @Autowired UserService users;
  @Autowired ProfileService profiles;
  @Autowired FriendService friends;
  @Autowired RewardService rewards;
  @Autowired SnapshotService snapshots;
  @Autowired LiveController live;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  static final Instant START = GameplayIT.START;

  @BeforeEach
  void clean() {
    live.closeAll();
    clock.now = START;
    reset.reset();
    db.update("insert into friendship values(?,?)", user("alice").id(), user("dave").id());
  }

  @AfterEach
  void closeStreams() {
    live.closeAll();
  }

  UserService.UserResponse user(String name) {
    return users.byUsername(name);
  }

  long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }

  long events(UUID round) {
    return db.queryForObject(
        "select count(*) from match_event e join fixture f on f.id=e.fixture_id where f.round_id=?",
        Long.class,
        round);
  }

  TeamService.Lineup lineup(UUID id, String name) {
    var p = teams.autoPick(id, user(name), true);
    return new TeamService.Lineup(
        p.starters().stream().map(x -> x.id()).toList(), p.bench().id(), p.captainPlayerId());
  }

  TeamService.TeamSnapshot join(UUID id, String name) {
    return teams.create(id, user(name), lineup(id, name));
  }

  UUID prepareAlice() {
    loop.cycle();
    UUID id = loop.session().nextRound().id();
    join(id, "alice");
    clock.now = START.plusSeconds(180);
    loop.cycle();
    return id;
  }

  TeamService.Substitute swap(TeamService.TeamSnapshot t) {
    var out =
        t.starters().stream()
            .filter(
                p ->
                    t.starters().stream()
                        .filter(other -> !other.player().id().equals(p.player().id()))
                        .noneMatch(
                            other ->
                                other.player().countryId().equals(t.bench().player().countryId())))
            .findFirst()
            .orElseThrow();
    return new TeamService.Substitute(out.player().id(), t.bench().player().id(), t.version());
  }

  @Test
  void createsOneLiveAndOneOpenWithPlayableSquadsAcrossAllClubPairs() throws Exception {
    mvc.perform(get("/api/v1/demo/session")).andExpect(status().isUnauthorized());
    Set<UUID> clubs = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      clock.now = START.plusSeconds(i * 180L);
      loop.cycle();
      var s = loop.session();
      assertThat(s.enabled()).isTrue();
      assertThat(s.currentRound().status()).isEqualTo("LIVE");
      assertThat(s.currentRound().demoMode()).isTrue();
      assertThat(s.currentRound().endsAt()).isEqualTo(clock.now.plusSeconds(180));
      assertThat(s.currentRound().subWindowClosesAt()).isEqualTo(clock.now.plusSeconds(120));
      assertThat(s.currentRound().participantCount()).isEqualTo(8);
      assertThat(s.currentRound().rules().rewards()).isEqualTo(new Rules.Rewards(0, 0));
      assertThat(s.nextRound().status()).isEqualTo("OPEN");
      assertThat(s.nextRound().entryDeadline()).isEqualTo(s.currentRound().endsAt());
      assertThat(teams.mineOrNull(s.currentRound().id(), user("alice").id())).isNull();
      assertThat(teams.mineOrNull(s.currentRound().id(), user("dave").id())).isNull();
      assertThat(lineup(s.nextRound().id(), "alice").starterPlayerIds()).hasSize(5);
      clubs.addAll(
          db.queryForList(
              "select home_club_id from fixture where round_id=?",
              UUID.class,
              s.currentRound().id()));
      assertThat(events(s.currentRound().id())).isEqualTo(20);
    }
    assertThat(clubs).hasSize(3);
    mvc.perform(get("/api/v1/demo/session").header("X-Dev-User", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roundDurationSeconds").value(180))
        .andExpect(jsonPath("$.currentRound.demoMode").value(true));
    mvc.perform(get("/api/v1/rounds?status=OPEN&demoMode=true").header("X-Dev-User", "alice"))
        .andExpect(jsonPath("$.total").value(1));
    assertThat(rounds.list(null, null, null, "UTC", false, 0, 100).items())
        .allMatch(r -> !r.demoMode());
  }

  @Test
  void scoringSubstitutionsAndResultsNeverChangePermanentProgressOrRewards() {
    var before = profiles.profile(user("alice"));
    var ranking = profiles.rankings();
    var catalogue = rewards.catalogue(user("alice").id());
    var wallets = db.queryForList("select * from reward_wallet order by user_id");
    loop.cycle();
    UUID id = loop.session().nextRound().id();
    join(id, "alice");
    var invitation = friends.invite(user("alice").id(), user("dave").id(), id);
    friends.respond(user("dave").id(), invitation.id(), true);
    clock.now = START.plusSeconds(180);
    loop.cycle();
    var t = teams.mine(id, user("alice").id());
    assertThat(t.score()).isPositive();
    teams.substitute(t.id(), user("alice").id(), swap(t));
    clock.now = START.plusSeconds(239);
    loop.cycle();
    assertThat(teams.mine(id, user("alice").id()).pendingSubstitution()).isNotNull();
    clock.now = START.plusSeconds(240);
    loop.cycle();
    assertThat(teams.mine(id, user("alice").id()).pendingSubstitution()).isNull();
    // Even accidental changes to the rules cannot turn a practice game into a rewards source.
    db.update(
        "update app_round set rules=?::jsonb where id=?",
        json.writeValueAsString(Rules.defaults()),
        id);
    clock.now = START.plusSeconds(360);
    loop.cycle();
    var result = results.mine(id, user("alice").id());
    assertThat(result.demoMode()).isTrue();
    assertThat(result.finalScore()).isPositive();
    assertThat(result.totalAwardedRp()).isZero();
    assertThat(result.participationRp()).isZero();
    assertThat(result.winnerRp()).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from score_contribution where round_id=? and"
                    + " component='LATE_GOAL_BONUS'",
                Long.class,
                id))
        .isPositive();
    assertThat(db.queryForList("select * from reward_wallet order by user_id")).isEqualTo(wallets);
    assertThat(count("reward_ledger")).isZero();
    assertThat(profiles.profile(user("alice"))).isEqualTo(before);
    assertThat(profiles.rankings()).isEqualTo(ranking);
    assertThat(rewards.catalogue(user("alice").id())).isEqualTo(catalogue);
    assertThat(friends.friends(user("alice").id(), 0, 20).items().getFirst().leaderboardPoints())
        .isZero();
    assertThat(teams.mineOrNull(loop.session().nextRound().id(), user("alice").id())).isNull();
    assertThat(snapshots.get(id, user("alice").id()).payload().result()).isEqualTo(result);
    assertThat(snapshots.get(id, user("dave").id()).payload().result().teamId())
        .isNotEqualTo(result.teamId());
  }

  @Test
  void repeatedTicksAndLongDowntimeUseSavedCursorWithoutCatchupRounds() {
    loop.cycle();
    UUID first = loop.session().currentRound().id(), next = loop.session().nextRound().id();
    clock.now = START.plusSeconds(50);
    loop.cycle();
    var stored = db.queryForList("select * from match_event order by id");
    loop.cycle();
    assertThat(db.queryForList("select * from match_event order by id")).isEqualTo(stored);
    clock.now = START.plus(Duration.ofDays(2));
    loop.cycle();
    assertThat(loop.session().currentRound().id()).isEqualTo(next);
    assertThat(loop.session().currentRound().entryDeadline()).isEqualTo(clock.now);
    assertThat(rounds.get(first).status()).isEqualTo("SETTLED");
    assertThat(events(first)).isEqualTo(39);
    assertThat(count("demo_round")).isEqualTo(3);
    var savedResults = db.queryForList("select * from round_result order by team_id");
    loop.cycle();
    assertThat(db.queryForList("select * from round_result order by team_id"))
        .isEqualTo(savedResults);
    assertThat(count("reward_ledger")).isZero();
  }

  @Test
  void failedSettlementRollsBackRotationEventsAndCursorThenRetries() {
    loop.cycle();
    UUID first = loop.session().currentRound().id();
    db.execute("alter table round_result add constraint injected_failure check(final_score<0)");
    clock.now = START.plusSeconds(180);
    try {
      assertThatThrownBy(loop::cycle).isInstanceOf(RuntimeException.class);
      assertThat(rounds.get(first).status()).isEqualTo("LIVE");
      assertThat(events(first)).isEqualTo(20);
      assertThat(count("demo_round")).isEqualTo(2);
      assertThat(
              db.queryForObject(
                  "select event_cursor from demo_round where round_id=?", Integer.class, first))
          .isEqualTo(20);
    } finally {
      db.execute("alter table round_result drop constraint injected_failure");
    }
    loop.cycle();
    assertThat(rounds.get(first).status()).isEqualTo("SETTLED");
    assertThat(events(first)).isEqualTo(39);
    assertThat(count("round_result")).isEqualTo(8);
  }

  @Test
  void concurrentTicksCannotCreateExtraArenasOrDuplicateSettlement() throws Exception {
    try (var threads = Executors.newFixedThreadPool(2)) {
      var a = threads.submit(loop::cycle);
      var b = threads.submit(loop::cycle);
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
      assertThat(count("demo_round")).isEqualTo(2);
      clock.now = START.plusSeconds(180);
      a = threads.submit(loop::cycle);
      b = threads.submit(loop::cycle);
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
      assertThat(count("demo_round")).isEqualTo(3);
      assertThat(count("round_result")).isEqualTo(8);
      assertThat(count("reward_ledger")).isZero();
    }
  }

  @Test
  void cutoffAndManualControlsProtectManagedRoundsAndStreamCarriesMode() throws Exception {
    UUID id = prepareAlice();
    for (String action : List.of("open", "start", "settle"))
      mvc.perform(post("/api/v1/dev/rounds/" + id + "/" + action).header("X-Dev-User", "alice"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("DEMO_MANAGED"));
    mvc.perform(
            post("/api/v1/dev/simulator/start")
                .header("X-Dev-User", "alice")
                .contentType("application/json")
                .content("{\"roundId\":\"" + id + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DEMO_MANAGED"));
    var stream =
        mvc.perform(get("/api/v1/rounds/" + id + "/stream").header("X-Dev-User", "alice"))
            .andExpect(request().asyncStarted())
            .andReturn();
    assertThat(stream.getResponse().getContentAsString())
        .contains("event:ROUND_SNAPSHOT", "\"demoMode\":true", "\"endsAt\":");
    clock.now = START.plusSeconds(300);
    var t = teams.mine(id, user("alice").id());
    assertThatThrownBy(() -> teams.substitute(t.id(), user("alice").id(), swap(t)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("SUBSTITUTION_WINDOW_CLOSED"));
    assertThat(rounds.due()).doesNotContain(loop.session().nextRound().id());
  }

  @Test
  void pendingSubstitutionDoesNotBlockNextGameButDefersPreviousSettlement() {
    UUID id = prepareAlice();
    var t = teams.mine(id, user("alice").id());
    teams.substitute(t.id(), user("alice").id(), swap(t));
    // Recovery guard for a legacy or manually extended pending substitution.
    db.update(
        "update team_substitution set effective_at=? where team_id=?",
        java.sql.Timestamp.from(START.plusSeconds(370)),
        t.id());
    clock.now = START.plusSeconds(360);
    loop.cycle();
    assertThat(loop.session().currentRound().id()).isNotEqualTo(id);
    assertThat(rounds.get(id).status()).isEqualTo("LIVE");
    clock.now = START.plusSeconds(370);
    loop.cycle();
    assertThat(rounds.get(id).status()).isEqualTo("SETTLED");
  }

  @Test
  void retentionDeletesOnlyExpiredDemoDependenciesAndTheirCachedResponses() throws Exception {
    UUID normal = DemoSeed.id("round:1");
    var normalTeam = join(normal, "alice");
    lifecycle.start(normal, true);
    settlement.settle(normal);
    var normalResult = results.mine(normal, user("alice").id());
    var normalWallets = db.queryForList("select * from reward_wallet order by user_id");
    db.update(
        "insert into idempotency_record values(?, 'normal', '/normal','hash',200,?,?)",
        user("alice").id(),
        json.writeValueAsString(normalTeam),
        java.sql.Timestamp.from(START));
    loop.cycle();
    UUID expired = loop.session().nextRound().id();
    mvc.perform(
            post("/api/v1/rounds/" + expired + "/teams")
                .header("X-Dev-User", "alice")
                .header("Idempotency-Key", "demo-join")
                .contentType("application/json")
                .content(json.writeValueAsString(lineup(expired, "alice"))))
        .andExpect(status().isCreated());
    var invite = friends.invite(user("alice").id(), user("dave").id(), expired);
    friends.respond(user("dave").id(), invite.id(), true);
    clock.now = START.plusSeconds(180);
    loop.cycle();
    var t = teams.mine(expired, user("alice").id());
    teams.substitute(t.id(), user("alice").id(), swap(t));
    clock.now = START.plusSeconds(360);
    loop.cycle();
    clock.now = START.plusSeconds(360 + 86400 - 1);
    loop.cleanup();
    assertThat(rounds.get(expired).status()).isEqualTo("SETTLED");
    clock.now = START.plusSeconds(360 + 86400);
    assertThat(loop.cleanup()).isEqualTo(1);
    assertThatThrownBy(() -> rounds.get(expired)).isInstanceOf(ApiException.class);
    for (String table :
        List.of(
            "round_result",
            "round_invitation",
            "score_contribution",
            "team_score",
            "team",
            "round_player",
            "fixture",
            "round_presentation",
            "demo_round"))
      assertThat(
              db.queryForObject(
                  "select count(*) from " + table + " where round_id=?", Long.class, expired))
          .as(table)
          .isZero();
    assertThat(db.queryForList("select key from idempotency_record", String.class))
        .containsExactly("normal");
    assertThat(results.mine(normal, user("alice").id())).isEqualTo(normalResult);
    assertThat(db.queryForList("select * from reward_wallet order by user_id"))
        .isEqualTo(normalWallets);
    assertThat(count("app_user")).isEqualTo(10);
    assertThat(loop.session().currentRound()).isNotNull();
    assertThat(loop.session().nextRound()).isNotNull();
    mvc.perform(get("/api/v1/rounds/" + expired).header("X-Dev-User", "alice"))
        .andExpect(status().isNotFound());
    loop.cycle(); // Recovery still works after cleanup.
  }

  @Test
  void explicitFullResetCanRecreateLoopWithoutAnInMemoryCursor() {
    loop.cycle();
    UUID old = loop.session().currentRound().id();
    reset.reset();
    loop.cycle();
    assertThat(loop.session().currentRound().id()).isNotEqualTo(old);
    assertThat(count("demo_round")).isEqualTo(2);
    assertThat(events(loop.session().currentRound().id())).isEqualTo(20);
  }
}
