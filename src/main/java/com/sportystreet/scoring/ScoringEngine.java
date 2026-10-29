package com.sportystreet.scoring;

import com.sportystreet.rounds.Rules;
import java.time.Instant;
import java.util.*;

/** Pure scoring rules: no persistence, clock reads or Spring dependencies. */
public final class ScoringEngine {
  public enum EventType {
    APPEARANCE,
    GOAL,
    ASSIST,
    YELLOW_CARD,
    RED_CARD,
    OWN_GOAL
  }

  public record Event(
      UUID id,
      UUID fixtureId,
      UUID playerId,
      EventType type,
      int matchMinute,
      Instant occurredAt) {}

  public record Selection(
      UUID playerId, UUID countryId, String role, Instant effectiveFrom, Instant effectiveTo) {
    public boolean activeAt(Instant at) {
      return role.equals("STARTER")
          && !at.isBefore(effectiveFrom)
          && (effectiveTo == null || at.isBefore(effectiveTo));
    }
  }

  public record TeamContext(
      UUID id, UUID captainPlayerId, UUID homeCountryId, List<Selection> selections) {}

  public record Contribution(
      UUID teamId, UUID playerId, UUID eventId, String component, int points, String explanation) {}

  private ScoringEngine() {}

  public static List<Contribution> score(Event event, TeamContext team, Rules rules) {
    var active =
        team.selections().stream()
            .filter(s -> s.playerId().equals(event.playerId()) && s.activeAt(event.occurredAt()))
            .findFirst();
    if (active.isEmpty()) return List.of();
    var result = new ArrayList<Contribution>();
    int base = rules.base().get(event.type().name());
    add(result, event, team, "BASE", base, event.type().name());
    int late =
        event.type() == EventType.GOAL && event.matchMinute() >= rules.twist().fromMatchMinute()
            ? base * (rules.twist().multiplier() - 1)
            : 0;
    add(
        result,
        event,
        team,
        "LATE_GOAL_BONUS",
        late,
        "Goal from minute " + rules.twist().fromMatchMinute());
    if (event.playerId().equals(team.captainPlayerId()))
      add(
          result,
          event,
          team,
          "CAPTAIN_BONUS",
          (base + late) * (rules.captainMultiplier() - 1),
          "Captain multiplier");
    if (team.homeCountryId() != null && team.homeCountryId().equals(active.get().countryId())) {
      int bonus =
          switch (event.type()) {
            case GOAL -> rules.homeHero().goal();
            case ASSIST -> rules.homeHero().assist();
            default -> 0;
          };
      add(result, event, team, "HOME_HERO_BONUS", bonus, "Home Hero (fixed bonus)");
    }
    return List.copyOf(result);
  }

  private static void add(
      List<Contribution> result,
      Event event,
      TeamContext team,
      String type,
      int points,
      String explanation) {
    if (points != 0)
      result.add(
          new Contribution(team.id(), event.playerId(), event.id(), type, points, explanation));
  }
}
