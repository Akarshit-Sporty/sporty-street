package com.sportystreet;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.devtools.*;
import com.sportystreet.profile.*;
import com.sportystreet.rewards.RewardService;
import com.sportystreet.rounds.RoundService;
import com.sportystreet.shared.ApiException;
import com.sportystreet.users.UserService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest
@ActiveProfiles({"local", "test"})
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShowcaseIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired ShowcaseSeed seed;
  @Autowired RewardService rewards;
  @Autowired ProfileService profiles;
  @Autowired FriendService friends;
  @Autowired UserService users;
  @Autowired RoundService rounds;
  UUID alice = DemoSeed.id("user:alice");

  JsonNode getAs(String path, String user) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  UUID open() {
    return rounds.list("OPEN", 0, 100).items().stream()
        .filter(r -> r.participantCount() >= 8)
        .findFirst()
        .orElseThrow()
        .id();
  }

  @Test
  @Order(1)
  void profileHistoryAndBalancesComeFromPersistedScoring() {
    var p = profiles.profile(users.byUsername("alice"));
    assertThat(p.stats().roundsPlayed()).isEqualTo(40);
    assertThat(p.stats().averagePoints()).isPositive();
    assertThat(p.stats().wins()).isEqualTo(20);
    assertThat(p.stats().bestFinish()).isEqualTo(1);
    assertThat(p.stats().streakDays()).isEqualTo(40);
    assertThat(p.topHomeHero()).isNotNull();
    assertThat(p.progress().level()).isGreaterThan(1);
    assertThat(profiles.history(alice, 0, 100).total()).isEqualTo(40);
    assertThat(p.leaderboard().points()).isBetween(500L, 2999L);
    assertThat(rewards.wallet(alice).balanceRp()).isEqualTo(4000);
    assertThat(
            db.queryForObject(
                "select count(*) from round_result r where r.final_score<>(select"
                    + " coalesce(sum(c.points),0) from score_contribution c where"
                    + " c.team_id=r.team_id)",
                Long.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from reward_wallet w where w.balance<>(select"
                    + " coalesce(sum(delta),0) from reward_ledger l where l.user_id=w.user_id)",
                Long.class))
        .isZero();
  }

  @Test
  @Order(2)
  void seedIsAdditiveRepeatableAndExactlyEightyPercentHaveAvatars() {
    var before = db.queryForList("select * from reward_wallet order by user_id");
    long results = db.queryForObject("select count(*) from round_result", Long.class);
    long roundsBefore = db.queryForObject("select count(*) from app_round", Long.class);
    seed.run(null);
    seed.run(null);
    assertThat(db.queryForList("select * from reward_wallet order by user_id")).isEqualTo(before);
    assertThat(db.queryForObject("select count(*) from round_result", Long.class))
        .isEqualTo(results);
    assertThat(db.queryForObject("select count(*) from app_round", Long.class))
        .isEqualTo(roundsBefore);
    assertThat(db.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(10);
    assertThat(
            db.queryForObject(
                "select count(*) from app_user where avatar_url is not null", Long.class))
        .isEqualTo(8);
  }

  @Test
  @Order(3)
  void playPeriodsFormatsFixturesAndRealParticipants() throws Exception {
    for (var period : List.of("TODAY", "TOMORROW", "THIS_WEEK")) {
      var list = getAs("/api/v1/rounds?period=" + period + "&timeZone=UTC&size=100", "alice");
      assertThat(list.get("total").asInt()).isGreaterThanOrEqualTo(3);
      for (var r : list.get("items")) {
        assertThat(r.get("fixtures").size()).isPositive();
        assertThat(r.get("matchCount").asInt()).isEqualTo(r.get("fixtures").size());
        assertThat(r.get("participantCount").asInt()).isPositive();
      }
    }
    for (var r : rounds.list("OPEN", null, "QUICK", "UTC", 0, 100).items())
      assertThat(r.matchCount()).isEqualTo(1);
    assertThat(rounds.list("OPEN", null, "QUICK", "UTC", 0, 100).total()).isPositive();
    var board = getAs("/api/v1/rounds/" + open() + "/leaderboard", "alice");
    assertThat(board.get("standings").get("total").asInt()).isEqualTo(8);
    for (var key : List.of("period=NOPE", "format=NOPE", "timeZone=NOPE"))
      mvc.perform(get("/api/v1/rounds?" + key).header("X-Dev-User", "alice"))
          .andExpect(status().isBadRequest());
    var global = getAs("/api/v1/leaderboard", "alice");
    assertThat(global.get("total").asInt()).isEqualTo(10);
    getAs("/api/v1/me/profile", "alice");
    getAs("/api/v1/me/round-history?page=1&size=10", "alice");
  }

  @Test
  @Order(4)
  void rewardCatalogueHasTwoCategoriesImagesAndExpectedLocks() throws Exception {
    var c = rewards.catalogue(alice);
    assertThat(c.categories()).hasSize(2);
    assertThat(c.items()).hasSize(7);
    for (var o : c.items()) {
      mvc.perform(get(o.gift().imageUrl())).andExpect(status().isOk());
      if (o.gift().name().equals("Free Bet")
          || o.gift().name().equals("Referral Reward")
          || o.gift().name().equals("Sporty Red Cap")) assertThat(o.canClaim()).isTrue();
      else assertThat(o.canClaim()).isFalse();
    }
    for (String name :
        List.of("alice", "bob", "carol", "dave", "eve", "frank", "grace", "henry", "ivy", "jack"))
      assertThat(
              rewards.catalogue(users.byUsername(name).id()).items().stream()
                  .filter(o -> o.gift().name().equals("Referral Reward"))
                  .findFirst()
                  .orElseThrow()
                  .canClaim())
          .isTrue();
    mvc.perform(get("/api/v1/rewards/catalogue")).andExpect(status().isUnauthorized());
    getAs("/api/v1/rewards/catalogue", "alice");
    assertThatThrownBy(() -> rewards.purchase(alice, DemoSeed.id("reward:cashback")))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("REWARD_LOCKED"));
  }

  @Test
  @Order(5)
  void invitationPersistsForBothUsersAndRecipientAcceptsIntoRound() throws Exception {
    assertThat(friends.friends(alice, 0, 20).items())
        .extracting(FriendService.Friend::username)
        .containsExactlyInAnyOrder("bob", "carol", "dave");
    UUID dave = DemoSeed.id("user:dave"), round = open();
    String path = "/api/v1/friends/" + dave + "/invitations",
        body = "{\"roundId\":\"" + round + "\"}";
    var response =
        mvc.perform(
                post(path)
                    .header("X-Dev-User", "alice")
                    .header("Idempotency-Key", "invite-dave")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post(path)
                .header("X-Dev-User", "alice")
                .header("Idempotency-Key", "invite-dave")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(content().json(response));
    UUID id = UUID.fromString(json.readTree(response).get("id").asText());
    assertThat(friends.invitations(dave, 0, 20).total()).isEqualTo(1);
    assertThat(friends.invitations(DemoSeed.id("user:eve"), 0, 20).total()).isZero();
    mvc.perform(post("/api/v1/invitations/" + id + "/accept").header("X-Dev-User", "alice"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/invitations/" + id + "/accept").header("X-Dev-User", "dave"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
    assertThat(friends.respond(dave, id, true).status()).isEqualTo("ACCEPTED");
    getAs("/api/v1/rounds/" + round + "/my-team", "dave");
    assertThat(
            db.queryForObject(
                "select count(*) from team where user_id=? and round_id=?",
                Long.class,
                dave,
                round))
        .isEqualTo(1);
    var bobInvite = friends.invite(alice, DemoSeed.id("user:bob"), round);
    assertThat(friends.respond(DemoSeed.id("user:bob"), bobInvite.id(), false).status())
        .isEqualTo("DECLINED");
    assertThatThrownBy(() -> friends.invite(alice, DemoSeed.id("user:eve"), round))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> friends.invite(alice, dave, DemoSeed.id("history:1")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @Order(6)
  void freeClaimsAreIdempotentAndCannotRepeatWithNewKeys() throws Exception {
    int before = rewards.wallet(alice).balanceRp();
    long points = profiles.ranking(alice).points();
    String path = "/api/v1/gifts/" + DemoSeed.id("reward:free-bet") + "/purchases";
    var first =
        mvc.perform(post(path).header("X-Dev-User", "alice").header("Idempotency-Key", "freebet"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.purchase.priceRp").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(post(path).header("X-Dev-User", "alice").header("Idempotency-Key", "freebet"))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    mvc.perform(post(path).header("X-Dev-User", "alice").header("Idempotency-Key", "freebet-again"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REWARD_ALREADY_CLAIMED"));
    assertThat(rewards.wallet(alice).balanceRp()).isEqualTo(before);
    assertThat(profiles.ranking(alice).points()).isEqualTo(points);
  }

  @Test
  @Order(7)
  void concurrentReferralClaimsIssueOneVoucher() {
    var user = DemoSeed.id("user:ivy");
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<String>>();
      for (int i = 0; i < 5; i++)
        jobs.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    rewards.purchase(user, DemoSeed.id("reward:referral"));
                    return "OK";
                  } catch (ApiException e) {
                    return e.code();
                  }
                }));
      gate.countDown();
      var out = new ArrayList<String>();
      for (var job : jobs) out.add(job.get(10, TimeUnit.SECONDS));
      assertThat(out.stream().filter("OK"::equals)).hasSize(1);
      assertThat(out.stream().filter("REWARD_ALREADY_CLAIMED"::equals)).hasSize(4);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @Order(8)
  void merchDebitAndSeedRestartDoNotRestoreSpentBalance() {
    int before = rewards.wallet(alice).balanceRp();
    var bought = rewards.purchase(alice, DemoSeed.id("reward:cap"));
    assertThat(bought.balanceRp()).isEqualTo(before - 3000);
    seed.run(null);
    assertThat(rewards.wallet(alice).balanceRp()).isEqualTo(before - 3000);
    assertThatThrownBy(() -> rewards.purchase(alice, DemoSeed.id("reward:hoodie")))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_RP"));
    assertThat(
            db.queryForObject(
                "select balance from reward_wallet where user_id=?", Integer.class, alice))
        .isEqualTo(
            db.queryForObject(
                "select sum(delta) from reward_ledger where user_id=?", Integer.class, alice));
  }
}
