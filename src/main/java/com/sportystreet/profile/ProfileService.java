package com.sportystreet.profile;

import com.sportystreet.shared.*;
import com.sportystreet.users.UserService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProfileService {
  public record Ranking(
      UUID userId,
      String username,
      String displayName,
      String avatarUrl,
      long points,
      long rank,
      long roundsPlayed) {}

  public record History(
      UUID roundId,
      String name,
      String status,
      Instant startsAt,
      int score,
      Long rank,
      int earnedRp) {}

  public record Badge(String code, String name, boolean unlocked) {}

  public record Hero(UUID playerId, String name, String imageUrl, long bonusPoints) {}

  public record Stats(
      long roundsPlayed,
      double averagePoints,
      Long bestFinish,
      long wins,
      long substitutions,
      long smartSubs,
      int streakDays) {}

  public record Progress(long xp, long level, long xpInLevel, long xpForNextLevel, String title) {}

  public record Profile(
      UserService.UserResponse user,
      String bio,
      Instant joinedAt,
      Stats stats,
      Progress progress,
      Ranking leaderboard,
      List<Badge> badges,
      Hero topHomeHero,
      PageResponse<History> roundHistory) {}

  private final JdbcTemplate db;
  private final Clock clock;

  public ProfileService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public List<Ranking> rankings() {
    return db.query(
        """
select u.id,u.username,u.display_name,u.avatar_url,coalesce(sum(r.final_score),0) points,
 count(r.team_id) rounds,rank() over(order by coalesce(sum(r.final_score),0) desc) place
from app_user u left join team t on t.user_id=u.id left join round_result r on r.team_id=t.id
group by u.id order by points desc,u.id
""",
        (rs, n) ->
            new Ranking(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getLong("points"),
                rs.getLong("place"),
                rs.getLong("rounds")));
  }

  public Ranking ranking(UUID user) {
    return rankings().stream()
        .filter(r -> r.userId().equals(user))
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  public PageResponse<History> history(UUID user, int page, int size) {
    return PageResponse.of(
        db.query(
            """
select r.id,r.name,r.status,r.entry_deadline,coalesce(rr.final_score,s.total,0) score,rr.final_rank,
coalesce(rr.participation_rp+rr.winner_rp,0) rp from team t join app_round r on r.id=t.round_id
left join team_score s on s.team_id=t.id left join round_result rr on rr.team_id=t.id
where t.user_id=? order by r.entry_deadline desc,r.id
""",
            (rs, n) ->
                new History(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getTimestamp("entry_deadline").toInstant(),
                    rs.getInt("score"),
                    rs.getObject("final_rank", Long.class),
                    rs.getInt("rp")),
            user),
        page,
        size);
  }

  public Profile profile(UserService.UserResponse user) {
    var rank = ranking(user.id());
    var stats =
        db.queryForMap(
            """
select min(r.final_rank) best, count(*) filter(where r.final_rank=1) wins,coalesce(sum(t.subs_used),0) subs,
min(r.settled_at) joined from team t join round_result r on r.team_id=t.id where t.user_id=?
""",
            user.id());
    var dates =
        db.query(
            "select distinct (r.settled_at at time zone 'UTC')::date from round_result r join team"
                + " t on t.id=r.team_id where t.user_id=? order by 1 desc",
            (rs, n) -> rs.getDate(1).toLocalDate(),
            user.id());
    int streak = 0;
    var day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (!dates.isEmpty() && dates.getFirst().equals(day.minusDays(1))) day = day.minusDays(1);
    for (var date : dates) {
      if (!date.equals(day)) break;
      streak++;
      day = day.minusDays(1);
    }
    var heroes =
        db.query(
            """
select p.id,p.name,p.image_url,sum(c.points) points from score_contribution c join team t on t.id=c.team_id
join player p on p.id=c.player_id where t.user_id=? and c.component='HOME_HERO_BONUS'
group by p.id order by points desc,p.id limit 1
""",
            (rs, n) ->
                new Hero(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4)),
            user.id());
    long smart =
        db.queryForObject(
            """
select count(*) from team_substitution s join team t on t.id=s.team_id where t.user_id=? and
(select coalesce(sum(c.points),0) from score_contribution c join match_event e on e.id=c.match_event_id
 where c.team_id=t.id and c.player_id=s.incoming_player_id and e.occurred_at>=s.effective_at)>0
""",
            Long.class,
            user.id());
    long wins = ((Number) stats.get("wins")).longValue(),
        xp = rank.roundsPlayed() * 100 + rank.points();
    var badges =
        List.of(
            new Badge("SUPER_SUB", "Super Sub", smart > 0),
            new Badge("STREAK", "7-day streak", streak >= 7),
            new Badge("PATRIOT", "Home Hero", !heroes.isEmpty()),
            new Badge(
                "RECRUITER",
                "Team Player",
                db.queryForObject(
                        "select count(*) from round_invitation where sender_id=? and"
                            + " status='ACCEPTED'",
                        Long.class,
                        user.id())
                    > 0),
            new Badge("WINNER", "Winner", wins > 0));
    Instant joined =
        stats.get("joined") == null ? null : ((java.sql.Timestamp) stats.get("joined")).toInstant();
    return new Profile(
        user,
        "Football fan · Building my world five",
        joined,
        new Stats(
            rank.roundsPlayed(),
            rank.roundsPlayed() == 0
                ? 0
                : Math.round(rank.points() * 10.0 / rank.roundsPlayed()) / 10.0,
            stats.get("best") == null ? null : ((Number) stats.get("best")).longValue(),
            wins,
            ((Number) stats.get("subs")).longValue(),
            smart,
            streak),
        new Progress(
            xp,
            1 + xp / 1000,
            xp % 1000,
            1000,
            xp >= 5000 ? "All-Star" : xp >= 2000 ? "Playmaker" : "Rookie"),
        rank,
        badges,
        heroes.isEmpty() ? null : heroes.getFirst(),
        history(user.id(), 0, 10));
  }
}
