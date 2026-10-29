package com.sportystreet.devtools;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@org.springframework.core.annotation.Order(1)
public class DemoSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final FootballCatalogue catalogue;

  public DemoSeed(JdbcTemplate db, FootballCatalogue catalogue) {
    this.db = db;
    this.catalogue = catalogue;
  }

  public static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    for (var country : catalogue.data().countries()) {
      db.update(
          "insert into country values (?, ?, ?) on conflict do nothing",
          id("country:" + country.code()),
          country.code(),
          country.name());
    }
    String[] names = {
      "alice", "bob", "carol", "dave", "eve", "frank", "grace", "henry", "ivy", "jack"
    };
    String[] homeCountries = {"BRA", "FRA", "ENG", "ESP", "ARG", "DEU"};
    for (int i = 0; i < names.length; i++) {
      db.update(
          "insert into app_user (id,username,display_name,country_id) values (?, ?, ?, ?) on"
              + " conflict do nothing",
          id("user:" + names[i]),
          names[i],
          Character.toUpperCase(names[i].charAt(0)) + names[i].substring(1),
          i < 6 ? id("country:" + homeCountries[i]) : null);
      if (i < 8)
        db.update(
            "update app_user set avatar_url=? where id=? and avatar_url is null",
            "/media/avatars/" + names[i] + ".jpg",
            id("user:" + names[i]));
    }
  }
}
