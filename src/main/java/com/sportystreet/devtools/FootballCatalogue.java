package com.sportystreet.devtools;

import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A fixed historical roster, loaded locally without an external sports API. */
@Component
@Profile("local")
public class FootballCatalogue {
  public record Country(String code, String name) {}

  public record Player(
      String name,
      String position,
      String countryCode,
      String slug,
      String wikipediaTitle,
      String imageUrl) {}

  public record Club(String name, String countryCode, String sourceUrl, List<Player> players) {}

  public record Data(
      String season, String description, List<Country> countries, List<Club> clubs) {}

  private final Data data;

  public FootballCatalogue(ObjectMapper json) throws IOException {
    try (var input = new ClassPathResource("seed/football-2024-25.json").getInputStream()) {
      data = json.readValue(input, Data.class);
    }
  }

  public Data data() {
    return data;
  }
}
