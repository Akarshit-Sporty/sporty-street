package com.sportystreet;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.devtools.*;
import java.util.*;
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
class CommunityIT {
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
  @Autowired ResetService reset;
  @Autowired DemoSeed seed;
  final UUID lagos = DemoSeed.id("community-league:lagos-ballers");
  final UUID office = DemoSeed.id("community-league:office-five");
  final UUID round = DemoSeed.id("round:1");

  @BeforeEach
  void clean() {
    reset.reset();
  }

  JsonNode getAs(String path, String user) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void avatarsArePublicAndIncludedInUserAndLeaderboardResponses() throws Exception {
    for (String user : List.of("alice", "bob", "carol", "dave", "eve", "frank", "grace", "henry")) {
      var profile = getAs("/api/v1/me", user);
      String avatar = profile.get("avatarUrl").asText();
      assertThat(avatar).isEqualTo("/media/avatars/" + user + ".jpg");
      mvc.perform(get(avatar))
          .andExpect(status().isOk())
          .andExpect(content().contentType("image/jpeg"));
    }
    var leaderboard = getAs("/api/v1/rounds/" + round + "/leaderboard", "alice");
    for (var entry : leaderboard.get("standings").get("items")) {
      assertThat(entry.get("avatarUrl").asText()).startsWith("/media/avatars/");
    }
    for (var kind : List.of("avatars", "leagues")) {
      var credits =
          json.readTree(
              mvc.perform(get("/media/" + kind + "/credits.json"))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(credits.size()).isEqualTo(kind.equals("avatars") ? 12 : 6);
      for (var credit : credits) {
        assertThat(credit.get("author").asText()).isNotBlank();
        assertThat(credit.get("license").asText()).isNotBlank();
        mvc.perform(head(credit.get("imageUrl").asText())).andExpect(status().isOk());
      }
    }
  }

  @Test
  void backfillIsNonDestructiveAndKeepsExistingCustomAvatars() {
    var before =
        db.queryForList("select id,username,display_name,country_id from app_user order by id");
    db.update("update app_user set avatar_url=null where username='alice'");
    db.update("update app_user set avatar_url='/media/avatars/eve.jpg' where username='bob'");
    seed.run(null);
    seed.run(null);
    assertThat(
            db.queryForList("select id,username,display_name,country_id from app_user order by id"))
        .isEqualTo(before);
    assertThat(
            db.queryForObject(
                "select avatar_url from app_user where username='alice'", String.class))
        .isEqualTo("/media/avatars/alice.jpg");
    assertThat(
            db.queryForObject("select avatar_url from app_user where username='bob'", String.class))
        .isEqualTo("/media/avatars/eve.jpg");
  }

  @Test
  void leagueListsPersonalizeMembershipAndProvideArtworkAndStandings() throws Exception {
    var mine = getAs("/api/v1/leagues", "alice");
    assertThat(mine.get("dataMode").asText()).isEqualTo("SIMULATED");
    assertThat(mine.get("total").asInt()).isEqualTo(4);
    assertThat(getAs("/api/v1/leagues?scope=DISCOVER", "alice").get("total").asInt()).isEqualTo(2);
    var all = getAs("/api/v1/leagues?scope=ALL", "alice");
    assertThat(all.get("total").asInt()).isEqualTo(6);
    for (var league : all.get("items")) {
      mvc.perform(get(league.get("imageUrl").asText())).andExpect(status().isOk());
      mvc.perform(get(league.get("avatarUrl").asText())).andExpect(status().isOk());
    }
    var detail = getAs("/api/v1/leagues/" + lagos, "alice");
    assertThat(detail.get("members").size()).isEqualTo(12);
    assertThat(detail.get("league").get("myRank").asInt()).isEqualTo(2);
    assertThat(detail.get("members").get(1).get("profile").get("isYou").asBoolean()).isTrue();
    var page = getAs("/api/v1/leagues/" + lagos + "/table?page=1&size=5", "alice");
    assertThat(page.get("items").size()).isEqualTo(5);
    assertThat(page.get("items").get(0).get("rank").asInt()).isEqualTo(6);
    var bob = getAs("/api/v1/leagues/" + lagos, "bob");
    assertThat(bob.get("league").get("joined").asBoolean()).isFalse();
    assertThat(bob.get("league").get("myRank").isNull()).isTrue();
  }

  @Test
  void rivalriesAndChallengesAreViewerScopedAndReverseCorrectly() throws Exception {
    var alice = getAs("/api/v1/leagues/" + lagos + "/rivalries", "alice");
    assertThat(alice.get("items").size()).isEqualTo(4);
    assertThat(alice.get("challenges").size()).isEqualTo(3);
    assertThat(alice.get("items").get(0).get("wins").asInt()).isEqualTo(3);
    assertThat(alice.get("items").get(0).get("losses").asInt()).isEqualTo(4);
    var bobLagos = getAs("/api/v1/leagues/" + lagos + "/rivalries", "bob");
    assertThat(bobLagos.get("items").size()).isZero();
    assertThat(bobLagos.get("challenges").size()).isZero();
    var bob = getAs("/api/v1/leagues/" + office + "/rivalries", "bob").get("items").get(0);
    assertThat(bob.get("wins").asInt()).isEqualTo(2);
    assertThat(bob.get("losses").asInt()).isEqualTo(3);
    assertThat(bob.get("recentForm").get(0).asText()).isEqualTo("L");
    assertThat(bob.get("you").get("isYou").asBoolean()).isTrue();
    assertThat(bob.get("opponent").get("displayName").asText()).isEqualTo("Alice");
  }

  @Test
  void banterHasDiverseAuthorsTextsReactionsAndQuickMessages() throws Exception {
    var feed = getAs("/api/v1/leagues/" + lagos + "/banter", "alice");
    var texts = new HashSet<String>();
    var authors = new HashSet<String>();
    assertThat(feed.get("messages").get("total").asInt()).isEqualTo(8);
    for (var message : feed.get("messages").get("items")) {
      texts.add(message.get("text").asText());
      authors.add(message.get("author").get("id").asText());
      assertThat(message.get("reactions").size()).isEqualTo(4);
      assertThat(message.get("author").get("avatarUrl").asText()).startsWith("/media/avatars/");
    }
    assertThat(texts).hasSize(8);
    assertThat(authors).hasSize(8);
    assertThat(feed.get("quickMessages").size()).isEqualTo(12);
    assertThat(
            getAs("/api/v1/leagues/" + lagos + "/banter?page=1&size=3", "alice")
                .get("messages")
                .get("items")
                .size())
        .isEqualTo(3);
  }

  @Test
  void managerFiltersHaveCandidatesAndNeverWriteTeamsOrWallets() throws Exception {
    var teamsBefore = db.queryForList("select * from team order by id");
    var walletsBefore = db.queryForList("select * from reward_wallet order by user_id");
    var expected = Map.of("ALL", 14, "FRIENDS", 7, "CREATORS", 4, "TOP_FORM", 9);
    for (var entry : expected.entrySet()) {
      var response =
          getAs("/api/v1/backing/managers?roundId=" + round + "&filter=" + entry.getKey(), "alice");
      assertThat(response.get("total").asInt()).isEqualTo(entry.getValue());
      assertThat(response.get("readOnly").asBoolean()).isTrue();
      for (var manager : response.get("items")) {
        assertThat(manager.get("profile").get("isYou").asBoolean()).isFalse();
        if (entry.getKey().equals("FRIENDS"))
          assertThat(manager.get("friend").asBoolean()).isTrue();
        if (entry.getKey().equals("CREATORS"))
          assertThat(manager.get("profile").get("creator").asBoolean()).isTrue();
        if (entry.getKey().equals("TOP_FORM"))
          assertThat(manager.get("topForm").asBoolean()).isTrue();
        assertThat(manager.get("squadPreview").size()).isEqualTo(5);
        assertThat(manager.has("teamId")).isFalse();
      }
    }
    assertThat(db.queryForList("select * from team order by id")).isEqualTo(teamsBefore);
    assertThat(db.queryForList("select * from reward_wallet order by user_id"))
        .isEqualTo(walletsBefore);
    assertThat(
            getAs("/api/v1/backing/managers?roundId=" + round + "&filter=FRIENDS", "bob")
                .get("total")
                .asInt())
        .isPositive();
  }

  @Test
  void validationAuthenticationAndReadOnlyBoundary() throws Exception {
    mvc.perform(get("/api/v1/leagues")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/leagues?scope=INVALID").header("X-Dev-User", "alice"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/leagues?page=-1").header("X-Dev-User", "alice"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/leagues/" + UUID.randomUUID()).header("X-Dev-User", "alice"))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/backing/managers?roundId=" + round + "&filter=INVALID")
                .header("X-Dev-User", "alice"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/backing/managers?roundId=" + UUID.randomUUID())
                .header("X-Dev-User", "alice"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/leagues/" + lagos + "/banter").header("X-Dev-User", "alice"))
        .andExpect(status().isMethodNotAllowed());
  }
}
