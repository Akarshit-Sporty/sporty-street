package com.sportystreet.devtools;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.ingestion.IngestionService;
import com.sportystreet.rounds.*;
import com.sportystreet.scoring.ScoringEngine.EventType;
import com.sportystreet.shared.ApiException;
import com.sportystreet.teams.TeamService;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
public class SimulatorService {
  public record Status(
      UUID roundId, boolean running, boolean completed, int currentMatchMinute, String lastError) {}

  private record Delivery(
      int deliveryMinute,
      int minute,
      CatalogueService.PlayerResponse player,
      EventType type,
      String key,
      int revision,
      boolean voided) {
    Delivery(int minute, CatalogueService.PlayerResponse player, EventType type, String key) {
      this(minute, minute, player, type, key, 1, false);
    }
  }

  private final RoundService rounds;
  private final CatalogueService catalogue;
  private final IngestionService ingestion;
  private final Clock clock;
  private final SettlementService settlement;
  private final TeamService teams;
  private UUID roundId;
  private boolean running;
  private boolean completed;
  private String lastError;
  private int cursor;
  private List<Delivery> script = List.of();
  private final Set<String> deliveredCutoffs = new HashSet<>();

  public SimulatorService(
      RoundService rounds,
      CatalogueService catalogue,
      IngestionService ingestion,
      Clock clock,
      SettlementService settlement,
      TeamService teams) {
    this.rounds = rounds;
    this.catalogue = catalogue;
    this.ingestion = ingestion;
    this.clock = clock;
    this.settlement = settlement;
    this.teams = teams;
  }

  public synchronized Status start(UUID id) {
    rounds.requireManual(id);
    if (running) {
      if (!id.equals(roundId))
        throw new ApiException(409, "ROUND_BUSY", "Another simulation is running");
      return status();
    }
    if (!rounds.get(id).status().equals("LIVE"))
      throw ApiException.rule("ROUND_NOT_LIVE", "Start round first");
    roundId = id;
    cursor = 0;
    completed = false;
    lastError = null;
    deliveredCutoffs.clear();
    var players = catalogue.players(id, null);
    var deliveries = new ArrayList<Delivery>();
    for (var player : players)
      deliveries.add(new Delivery(0, player, EventType.APPEARANCE, "appearance-" + player.id()));
    // Repeated deliveries use precisely the same identity and payload.
    if (!deliveries.isEmpty()) deliveries.add(deliveries.getFirst());
    for (int minute : new int[] {5, 15, 30, 74, 75, 81})
      for (var player : players)
        deliveries.add(
            new Delivery(
                minute,
                player,
                minute == 15
                    ? EventType.ASSIST
                    : minute == 30 ? EventType.YELLOW_CARD : EventType.GOAL,
                "event-" + minute + "-" + player.id()));
    if (!players.isEmpty()) {
      var player = players.getFirst();
      deliveries.add(new Delivery(35, player, EventType.GOAL, "voided-goal"));
      deliveries.add(new Delivery(40, 35, player, EventType.GOAL, "voided-goal", 2, true));
      deliveries.add(new Delivery(50, 20, player, EventType.GOAL, "delayed-goal", 1, false));
      deliveries.add(new Delivery(55, player, EventType.GOAL, "corrected-event"));
      deliveries.add(new Delivery(56, 55, player, EventType.ASSIST, "corrected-event", 3, false));
      deliveries.add(new Delivery(57, 55, player, EventType.GOAL, "corrected-event", 2, false));
    }
    deliveries.sort(Comparator.comparingInt(Delivery::deliveryMinute));
    script = List.copyOf(deliveries);
    running = true;
    return status();
  }

  public synchronized SettlementService.Summary settle(UUID id) {
    rounds.requireManual(id);
    if (running && id.equals(roundId))
      throw new ApiException(409, "ROUND_BUSY", "Stop or finish the simulator before settlement");
    return settlement.settle(id);
  }

  public synchronized Status stop() {
    running = false;
    return status();
  }

  public synchronized Status status() {
    int minute =
        roundId == null
            ? 0
            : (int)
                Math.max(
                    0,
                    Math.min(
                        90,
                        Duration.between(rounds.get(roundId).entryDeadline(), clock.instant())
                                .getSeconds()
                            / 2));
    return new Status(roundId, running, completed, minute, lastError);
  }

  public synchronized void reset() {
    running = false;
    completed = false;
    roundId = null;
    cursor = 0;
    lastError = null;
    script = List.of();
    deliveredCutoffs.clear();
  }

  @Scheduled(fixedDelay = 500)
  public synchronized void tick() {
    if (!running) return;
    try {
      var round = rounds.get(roundId);
      if (!round.status().equals("LIVE")) {
        running = false;
        return;
      }
      int sent = 0;
      while (cursor < script.size() && sent++ < 10) {
        var e = script.get(cursor);
        if (round.entryDeadline().plusSeconds(e.deliveryMinute() * 2L).isAfter(clock.instant()))
          break;
        ingestion.accept(
            new IngestionService.MatchEventMessage(
                "simulator",
                roundId + ":" + e.key(),
                e.revision(),
                e.player().fixtureId(),
                e.player().id(),
                e.type(),
                e.minute(),
                round.entryDeadline().plusSeconds(e.minute() * 2L),
                e.voided()),
            "simulator");
        cursor++;
      }
      for (var sub : teams.substitutions(roundId)) {
        String key =
            sub.outgoingPlayerId() + ":" + sub.incomingPlayerId() + ":" + sub.effectiveAt();
        if (deliveredCutoffs.contains(key)
            || clock.instant().isBefore(sub.effectiveAt().plusSeconds(2))) continue;
        cutoffGoal(
            key + ":out-before",
            sub.outgoingPlayerId(),
            sub.effectiveAt().minusMillis(1),
            round.entryDeadline());
        cutoffGoal(
            key + ":in-at", sub.incomingPlayerId(), sub.effectiveAt(), round.entryDeadline());
        deliveredCutoffs.add(key);
      }
      if (cursor == script.size()
          && !clock.instant().isBefore(round.entryDeadline().plusSeconds(180))) {
        running = false;
        completed = true;
      }
    } catch (RuntimeException e) {
      lastError = e.getMessage();
      running = false;
    }
  }

  private void cutoffGoal(String key, UUID playerId, Instant occurredAt, Instant start) {
    var player = catalogue.player(roundId, playerId, null);
    int minute = (int) (Duration.between(start, occurredAt).getSeconds() / 2);
    ingestion.accept(
        new IngestionService.MatchEventMessage(
            "simulator",
            roundId + ":cutoff:" + key,
            1,
            player.fixtureId(),
            player.id(),
            EventType.GOAL,
            minute,
            occurredAt,
            false),
        "simulator");
  }
}
