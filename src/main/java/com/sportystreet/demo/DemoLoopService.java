package com.sportystreet.demo;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.devtools.ShowcaseSeed;
import com.sportystreet.ingestion.IngestionService;
import com.sportystreet.rounds.*;
import com.sportystreet.scoring.ScoringEngine.EventType;
import com.sportystreet.teams.TeamService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A single transaction rotates the arena; cursor and event IDs survive process restarts. */
@Service
@Profile("local")
public class DemoLoopService {
  public static final int RETENTION_HOURS = 24;

  public record Session(
      boolean enabled,
      Instant serverTime,
      int roundDurationSeconds,
      int retentionHours,
      RoundService.RoundResponse currentRound,
      RoundService.RoundResponse nextRound) {}

  private record Delivery(CatalogueService.PlayerResponse player, EventType type, int minute) {}

  private final JdbcTemplate db;
  private final Clock clock;
  private final boolean enabled;
  private final ShowcaseSeed seed;
  private final RoundService rounds;
  private final RoundLifecycleService lifecycle;
  private final CatalogueService catalogue;
  private final IngestionService ingestion;
  private final SettlementService settlement;
  private final TeamService teams;
  private final EntityManager entities;

  public DemoLoopService(
      JdbcTemplate db,
      Clock clock,
      @Value("${sporty.demo.enabled:false}") boolean enabled,
      ShowcaseSeed seed,
      RoundService rounds,
      RoundLifecycleService lifecycle,
      CatalogueService catalogue,
      IngestionService ingestion,
      SettlementService settlement,
      TeamService teams,
      EntityManager entities) {
    this.db = db;
    this.clock = clock;
    this.enabled = enabled;
    this.seed = seed;
    this.rounds = rounds;
    this.lifecycle = lifecycle;
    this.catalogue = catalogue;
    this.ingestion = ingestion;
    this.settlement = settlement;
    this.teams = teams;
    this.entities = entities;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Session session() {
    var live = ids("LIVE");
    var open = ids("OPEN");
    return new Session(
        enabled,
        clock.instant(),
        Rules.DEMO_DURATION_SECONDS,
        RETENTION_HOURS,
        live.isEmpty() ? null : rounds.get(live.getLast()),
        open.isEmpty() ? null : rounds.get(open.getFirst()));
  }

  private List<UUID> ids(String status) {
    return db.queryForList(
        "select r.id from app_round r join demo_round d on d.round_id=r.id where r.status=? order"
            + " by r.entry_deadline,r.id",
        UUID.class,
        status);
  }

  private void lock() {
    db.queryForList("select pg_advisory_xact_lock(714034012)");
  }

  @Transactional
  public void cycle() {
    if (!enabled) return;
    lock();
    var live = ids("LIVE");
    if (live.isEmpty()) {
      var open = ids("OPEN");
      UUID first = open.isEmpty() ? seed.createPracticeRound(clock.instant()) : open.getFirst();
      lifecycle.start(first, true);
      entities.flush();
      live = ids("LIVE");
    }
    var completed = new HashSet<UUID>();
    for (UUID id : live) if (advance(id)) completed.add(id);
    UUID current = live.getLast();
    var currentRound = rounds.get(current);
    if (!clock.instant().isBefore(currentRound.endsAt())) {
      var open = ids("OPEN");
      UUID next = open.isEmpty() ? seed.createPracticeRound(clock.instant()) : open.getFirst();
      // Activate the next arena before closing the old one, in the same DB transaction.
      lifecycle.start(next, true);
      entities.flush();
      advance(next);
      current = next;
    }
    for (UUID id : completed) if (!teams.hasPendingSubstitution(id)) settlement.settle(id);
    entities.flush();
    if (ids("OPEN").isEmpty()) seed.createPracticeRound(rounds.get(current).endsAt());
  }

  private boolean advance(UUID id) {
    var round = rounds.get(id);
    var players = catalogue.players(id, null);
    var script = new ArrayList<Delivery>();
    for (var p : players) script.add(new Delivery(p, EventType.APPEARANCE, 0));
    int index = 0;
    for (int minute :
        new int[] {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 74, 75, 81, 85, 89}) {
      var type =
          minute == 30 || minute == 65
              ? EventType.YELLOW_CARD
              : minute % 15 == 0 && minute != 75 ? EventType.ASSIST : EventType.GOAL;
      script.add(new Delivery(players.get(index++ % Math.min(8, players.size())), type, minute));
    }
    int cursor =
        db.queryForObject(
            "select event_cursor from demo_round where round_id=?", Integer.class, id);
    int before = cursor;
    while (cursor < script.size()) {
      var e = script.get(cursor);
      Instant occurred = round.entryDeadline().plusSeconds(e.minute() * 2L);
      if (occurred.isAfter(clock.instant())) break;
      ingestion.accept(
          new IngestionService.MatchEventMessage(
              "demo-loop",
              id + ":" + cursor,
              1,
              e.player().fixtureId(),
              e.player().id(),
              e.type(),
              e.minute(),
              occurred,
              false),
          "demo-loop");
      cursor++;
    }
    if (cursor != before)
      db.update("update demo_round set event_cursor=? where round_id=?", cursor, id);
    return cursor == script.size() && !clock.instant().isBefore(round.endsAt());
  }

  /** Bounded deletion; never touches normal rounds, wallets, purchases or catalogue identities. */
  @Transactional
  public int cleanup() {
    lock();
    var expired =
        db.queryForList(
            """
            select r.id from app_round r join demo_round d on d.round_id=r.id
            where r.status='SETTLED' and r.settled_at<=?
            and not exists(select 1 from reward_ledger l where l.round_id=r.id)
            order by r.settled_at,r.id limit 100 for update of r
            """,
            UUID.class,
            Timestamp.from(clock.instant().minus(Duration.ofHours(RETENTION_HOURS))));
    for (UUID id : expired) {
      // All demo mutations return a top-level roundId. Remove their cached responses as well.
      db.update(
          "delete from idempotency_record where response_body::jsonb ->> 'roundId'=?",
          id.toString());
      db.update("delete from round_invitation where round_id=?", id);
      db.update("delete from round_result where round_id=?", id);
      db.update(
          "delete from team_substitution where team_id in(select id from team where round_id=?)",
          id);
      db.update("delete from score_contribution where round_id=?", id);
      db.update(
          "delete from match_event where fixture_id in(select id from fixture where round_id=?)",
          id);
      db.update("delete from team_score where round_id=?", id);
      db.update(
          "delete from team_selection where team_id in(select id from team where round_id=?)", id);
      db.update("delete from team where round_id=?", id);
      db.update("delete from round_player where round_id=?", id);
      db.update("delete from fixture where round_id=?", id);
      db.update("delete from round_presentation where round_id=?", id);
      db.update("delete from demo_round where round_id=?", id);
      db.update("delete from app_round where id=?", id);
    }
    return expired.size();
  }
}
