package com.sportystreet.scoring;

import com.sportystreet.rounds.Rules;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class ScoreService {
  public record Entry(UUID teamId, String displayName, long rank, int score, String avatarUrl) {}

  public record Line(
      UUID playerId,
      UUID eventId,
      String component,
      int points,
      String explanation,
      int matchMinute,
      Instant occurredAt,
      UUID fixtureId) {}

  public record PlayerScore(UUID playerId, int total, List<Line> components) {}

  public record Breakdown(UUID teamId, int total, List<PlayerScore> players, List<Line> timeline) {}

  private final JdbcTemplate db;

  public ScoreService(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(UUID roundId, UUID teamId) {
    db.update("insert into team_score values (?,?,0)", teamId, roundId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recalculate(
      UUID roundId,
      Rules rules,
      List<ScoringEngine.Event> events,
      List<ScoringEngine.TeamContext> teams) {
    db.update("delete from score_contribution where round_id=?", roundId);
    for (var team : teams) {
      int total = 0;
      for (var event : events)
        for (var c : ScoringEngine.score(event, team, rules)) {
          db.update(
              "insert into score_contribution values (?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              roundId,
              c.teamId(),
              c.playerId(),
              c.eventId(),
              c.component(),
              c.points(),
              c.explanation());
          total += c.points();
        }
      db.update("update team_score set total=? where team_id=?", total, team.id());
    }
  }

  public List<Entry> standings(UUID roundId) {
    return db.query(
        """
        select t.id,u.display_name,u.avatar_url,s.total,rank() over(order by s.total desc) rank
        from team_score s join team t on t.id=s.team_id join app_user u on u.id=t.user_id
        where s.round_id=? order by s.total desc,t.id
        """,
        (rs, n) ->
            new Entry(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getLong("rank"),
                rs.getInt("total"),
                rs.getString("avatar_url")),
        roundId);
  }

  public Breakdown breakdown(UUID teamId) {
    var lines =
        db.query(
            """
select c.*,e.match_minute,e.occurred_at,e.fixture_id from score_contribution c
join match_event e on e.id=c.match_event_id where c.team_id=? order by e.occurred_at,e.id,c.component
""",
            (rs, n) ->
                new Line(
                    rs.getObject("player_id", UUID.class),
                    rs.getObject("match_event_id", UUID.class),
                    rs.getString("component"),
                    rs.getInt("points"),
                    rs.getString("explanation"),
                    rs.getInt("match_minute"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("fixture_id", UUID.class)),
            teamId);
    Map<UUID, List<Line>> byPlayer = new LinkedHashMap<>();
    lines.forEach(l -> byPlayer.computeIfAbsent(l.playerId(), ignored -> new ArrayList<>()).add(l));
    return new Breakdown(
        teamId,
        lines.stream().mapToInt(Line::points).sum(),
        byPlayer.entrySet().stream()
            .map(
                e ->
                    new PlayerScore(
                        e.getKey(),
                        e.getValue().stream().mapToInt(Line::points).sum(),
                        e.getValue()))
            .toList(),
        lines);
  }
}
