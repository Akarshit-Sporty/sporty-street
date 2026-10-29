package com.sportystreet;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.devtools.DemoSeed;
import com.sportystreet.shared.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers
class FoundationIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired IdempotencyService idempotency;
  @Autowired DemoSeed seed;

  @Test
  void authenticationRulesAndDocs() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
    mvc.perform(get("/api/v1/me").header("X-Dev-User", "unknown"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").header("X-Dev-User", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("alice"));
    var login =
        mvc.perform(post("/api/v1/dev/login").param("username", "alice"))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
            .andReturn();
    mvc.perform(get("/api/v1/me").cookie(login.getResponse().getCookie("dev_user")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/rules").header("X-Dev-User", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.twist.fromMatchMinute").value(75))
        .andExpect(jsonPath("$.rewards.participationRp").value(50));
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/me']").exists());
    mvc.perform(post("/api/v1/internal/match-events").header("X-Dev-User", "alice"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").header("X-Provider-Token", "local-demo-provider-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void seedIsRepeatable() {
    seed.run(null);
    seed.run(null);
    assertThat(db.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(8);
    assertThat(db.queryForObject("select count(*) from country", Integer.class)).isEqualTo(21);
  }

  @Test
  void realCatalogueHasPublicBundledPortraitsAndRecognizableRewards() throws Exception {
    var players = db.queryForList("select name,image_url from player");
    assertThat(players).hasSize(60);
    for (var player : players) {
      assertThat((String) player.get("name")).doesNotStartWith("Player ");
      var image = (String) player.get("image_url");
      assertThat(image).startsWith("/media/players/");
      var response = mvc.perform(get(image)).andExpect(status().isOk()).andReturn().getResponse();
      assertThat(response.getContentType()).startsWith("image/");
      assertThat(response.getContentAsByteArray().length).isGreaterThan(1000);
    }
    assertThat(
            db.queryForMap(
                "select c.name club, n.code country from player p join club c on c.id=p.club_id"
                    + " join country n on n.id=p.country_id where p.name='Kylian Mbappé'"))
        .containsEntry("club", "Real Madrid")
        .containsEntry("country", "FRA");
    assertThat(db.queryForList("select name from app_round order by name", String.class))
        .containsExactly("Champions Night", "European Superstars");
    assertThat(db.queryForList("select name from gift order by price_rp", String.class))
        .containsExactly("Matchday Coffee", "Football Store Discount", "Club Scarf");
    mvc.perform(get("/media/players/credits.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(60));
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void idempotencyReplaysAndRejectsConflictingBody() {
    UUID user = DemoSeed.id("user:alice");
    String key = UUID.randomUUID().toString();
    var calls = new AtomicInteger();
    var first =
        idempotency.execute(
            user,
            key,
            "POST /test",
            Map.of("a", 1, "b", 2),
            201,
            () -> Map.of("execution", calls.incrementAndGet()));
    var retry =
        idempotency.execute(
            user,
            key,
            "POST /test",
            new LinkedHashMap<>(Map.of("b", 2, "a", 1)),
            201,
            () -> Map.of("execution", calls.incrementAndGet()));
    assertThat(retry).isEqualTo(first);
    assertThat(calls).hasValue(1);
    assertThatThrownBy(
            () -> idempotency.execute(user, key, "POST /test", Map.of("a", 2), 201, Map::of))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    assertThatThrownBy(() -> idempotency.execute(user, " ", "POST /test", Map.of(), 201, Map::of))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  void failedOperationRollsBackItsWritesAndDoesNotConsumeKey() {
    UUID user = DemoSeed.id("user:alice");
    String key = UUID.randomUUID().toString();
    String original =
        db.queryForObject("select display_name from app_user where id=?", String.class, user);
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    user,
                    key,
                    "POST /rollback",
                    Map.of(),
                    201,
                    () -> {
                      db.update(
                          "update app_user set display_name='should rollback' where id=?", user);
                      throw new IllegalStateException("failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            db.queryForObject("select display_name from app_user where id=?", String.class, user))
        .isEqualTo(original);
    assertThat(
            db.queryForObject(
                "select count(*) from idempotency_record where key=?", Integer.class, key))
        .isZero();
    assertThat(
            idempotency
                .execute(user, key, "POST /rollback", Map.of(), 201, () -> Map.of("ok", true))
                .status())
        .isEqualTo(201);
  }

  @Test
  void concurrentIdenticalRequestsRunBusinessOperationOnce() throws Exception {
    String key = UUID.randomUUID().toString();
    var calls = new AtomicInteger();
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<IdempotencyService.StoredResponse>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return idempotency.execute(
                      DemoSeed.id("user:alice"),
                      key,
                      "POST /concurrent",
                      Map.of(),
                      201,
                      () -> Map.of("execution", calls.incrementAndGet()));
                }));
      gate.countDown();
      var first = tasks.getFirst().get(15, TimeUnit.SECONDS);
      for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS)).isEqualTo(first);
    }
    assertThat(calls).hasValue(1);
  }
}
