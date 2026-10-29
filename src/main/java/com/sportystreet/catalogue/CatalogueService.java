package com.sportystreet.catalogue;

import com.sportystreet.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogueService {
  public record PlayerResponse(
      UUID id,
      String name,
      String position,
      UUID countryId,
      String countryName,
      UUID clubId,
      String clubName,
      String imageUrl,
      Map<String, Object> displayStats,
      UUID fixtureId,
      boolean eligible,
      boolean isHomeCountry) {}

  public record FixtureResponse(
      UUID id,
      UUID roundId,
      String provider,
      UUID homeClubId,
      String homeClubName,
      UUID awayClubId,
      String awayClubName,
      java.time.Instant kickoffAt) {}

  private final JdbcTemplate db;

  public CatalogueService(JdbcTemplate db) {
    this.db = db;
  }

  public List<PlayerResponse> players(UUID roundId, UUID homeCountry) {
    return db.query(
        """
        select p.*, c.name country_name, cl.name club_name, rp.fixture_id, rp.eligible
        from round_player rp join player p on p.id=rp.player_id join country c on c.id=p.country_id
        join club cl on cl.id=p.club_id where rp.round_id=? order by p.name,p.id
        """,
        (rs, n) ->
            new PlayerResponse(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("position"),
                rs.getObject("country_id", UUID.class),
                rs.getString("country_name"),
                rs.getObject("club_id", UUID.class),
                rs.getString("club_name"),
                rs.getString("image_url"),
                Map.of(),
                rs.getObject("fixture_id", UUID.class),
                rs.getBoolean("eligible"),
                homeCountry != null && homeCountry.equals(rs.getObject("country_id", UUID.class))),
        roundId);
  }

  public PlayerResponse player(UUID roundId, UUID playerId, UUID homeCountry) {
    return players(roundId, homeCountry).stream()
        .filter(p -> p.id().equals(playerId))
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  public List<FixtureResponse> fixtures(UUID roundId) {
    return db.query(
        """
select f.*, h.name home_name, a.name away_name from fixture f
join club h on h.id=f.home_club_id join club a on a.id=f.away_club_id where round_id=? order by f.id
""",
        (rs, n) ->
            new FixtureResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("round_id", UUID.class),
                rs.getString("provider"),
                rs.getObject("home_club_id", UUID.class),
                rs.getString("home_name"),
                rs.getObject("away_club_id", UUID.class),
                rs.getString("away_name"),
                rs.getTimestamp("kickoff_at").toInstant()),
        roundId);
  }

  public FixtureResponse fixture(UUID id) {
    UUID roundId =
        db
            .query(
                "select round_id from fixture where id=?",
                (rs, n) -> rs.getObject(1, UUID.class),
                id)
            .stream()
            .findFirst()
            .orElseThrow(() -> ApiException.rule("UNKNOWN_FIXTURE", "Unknown fixture"));
    return fixtures(roundId).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
  }
}
