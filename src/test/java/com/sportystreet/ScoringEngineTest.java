package com.sportystreet;

import static com.sportystreet.scoring.ScoringEngine.*;
import static org.assertj.core.api.Assertions.*;

import com.sportystreet.rounds.Rules;
import com.sportystreet.scoring.ScoringEngine;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ScoringEngineTest {
  static final UUID PLAYER = UUID.randomUUID(),
      COUNTRY = UUID.randomUUID(),
      TEAM = UUID.randomUUID(),
      FIXTURE = UUID.randomUUID();
  static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  static Stream<Arguments> cases() {
    var results = new ArrayList<Arguments>();
    for (var type : EventType.values())
      for (boolean captain : new boolean[] {false, true})
        for (boolean home : new boolean[] {false, true})
          for (int minute : new int[] {74, 75, 93}) {
            int base =
                switch (type) {
                  case APPEARANCE -> 1;
                  case GOAL -> 5;
                  case ASSIST -> 3;
                  case YELLOW_CARD -> -1;
                  case RED_CARD -> -3;
                  case OWN_GOAL -> 0;
                };
            int expected =
                base * (type == EventType.GOAL && minute >= 75 ? 2 : 1) * (captain ? 2 : 1)
                    + (home && (type == EventType.GOAL || type == EventType.ASSIST) ? 2 : 0);
            results.add(Arguments.of(type, captain, home, minute, expected));
          }
    return results.stream();
  }

  @ParameterizedTest
  @MethodSource("cases")
  void scoresEveryTypeAndMultiplier(
      EventType type, boolean captain, boolean home, int minute, int expected) {
    var event = new Event(UUID.randomUUID(), FIXTURE, PLAYER, type, minute, START);
    var team =
        new TeamContext(
            TEAM,
            captain ? PLAYER : UUID.randomUUID(),
            home ? COUNTRY : null,
            List.of(new Selection(PLAYER, COUNTRY, "STARTER", START, null)));
    assertThat(
            ScoringEngine.score(event, team, Rules.defaults()).stream()
                .mapToInt(Contribution::points)
                .sum())
        .isEqualTo(expected);
  }

  @Test
  void intervalsAreHalfOpenAndBenchNeverScores() {
    var cutoff = START.plusSeconds(60);
    var starter =
        new TeamContext(
            TEAM,
            PLAYER,
            COUNTRY,
            List.of(new Selection(PLAYER, COUNTRY, "STARTER", START, cutoff)));
    assertThat(
            ScoringEngine.score(
                new Event(
                    UUID.randomUUID(), FIXTURE, PLAYER, EventType.GOAL, 75, cutoff.minusNanos(1)),
                starter,
                Rules.defaults()))
        .hasSize(4);
    assertThat(
            ScoringEngine.score(
                new Event(UUID.randomUUID(), FIXTURE, PLAYER, EventType.GOAL, 75, cutoff),
                starter,
                Rules.defaults()))
        .isEmpty();
    var bench =
        new TeamContext(
            TEAM, PLAYER, COUNTRY, List.of(new Selection(PLAYER, COUNTRY, "BENCH", START, null)));
    assertThat(
            ScoringEngine.score(
                new Event(UUID.randomUUID(), FIXTURE, PLAYER, EventType.GOAL, 75, START),
                bench,
                Rules.defaults()))
        .isEmpty();
  }
}
