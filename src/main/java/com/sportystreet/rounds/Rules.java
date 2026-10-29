package com.sportystreet.rounds;

import java.util.Map;

public record Rules(
    int version,
    int starterCount,
    int benchCount,
    boolean distinctStarterCountries,
    Map<String, Integer> base,
    int captainMultiplier,
    HomeHero homeHero,
    Twist twist,
    int subActivationDelaySeconds,
    Rewards rewards) {
  public record HomeHero(
      @com.fasterxml.jackson.annotation.JsonProperty("GOAL") int goal,
      @com.fasterxml.jackson.annotation.JsonProperty("ASSIST") int assist,
      boolean repeatable,
      boolean multiplied) {}

  public record Twist(String type, int fromMatchMinute, int multiplier) {}

  public record Rewards(int participationRp, int winnerBonusRp) {}

  public static Rules defaults() {
    return new Rules(
        1,
        5,
        1,
        true,
        Map.of(
            "APPEARANCE",
            1,
            "GOAL",
            5,
            "ASSIST",
            3,
            "YELLOW_CARD",
            -1,
            "RED_CARD",
            -3,
            "OWN_GOAL",
            0),
        2,
        new HomeHero(2, 2, true, false),
        new Twist("LATE_GOALS_X2", 75, 2),
        60,
        new Rewards(50, 100));
  }
}
