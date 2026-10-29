package com.sportystreet.profile;

import com.sportystreet.rounds.RoundService;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FriendService {
  public record Friend(
      UUID id, String username, String displayName, String avatarUrl, long leaderboardPoints) {}

  public record Invitation(
      UUID id,
      Friend sender,
      Friend recipient,
      UUID roundId,
      String roundName,
      String status,
      Instant createdAt,
      Instant respondedAt) {}

  private final JdbcTemplate db;
  private final Clock clock;
  private final RoundService rounds;
  private final TeamService teams;
  private final UserService users;

  public FriendService(
      JdbcTemplate db, Clock clock, RoundService rounds, TeamService teams, UserService users) {
    this.db = db;
    this.clock = clock;
    this.rounds = rounds;
    this.teams = teams;
    this.users = users;
  }

  private Friend person(UUID id) {
    return db
        .query(
            """
select u.*,coalesce((select sum(r.final_score) from round_result r join team t on t.id=r.team_id where t.user_id=u.id and not exists(select 1 from demo_round d where d.round_id=r.round_id)),0) points
from app_user u where u.id=?
""",
            (rs, n) ->
                new Friend(
                    id,
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getString("avatar_url"),
                    rs.getLong("points")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  public PageResponse<Friend> friends(UUID user, int page, int size) {
    return PageResponse.of(
        db
            .queryForList(
                "select friend_id from friendship where user_id=? order by friend_id",
                UUID.class,
                user)
            .stream()
            .map(this::person)
            .toList(),
        page,
        size);
  }

  public PageResponse<Invitation> invitations(UUID user, int page, int size) {
    return PageResponse.of(
        db
            .queryForList(
                "select id from round_invitation where sender_id=? or recipient_id=? order by"
                    + " created_at desc,id",
                UUID.class,
                user,
                user)
            .stream()
            .map(this::get)
            .toList(),
        page,
        size);
  }

  private Invitation get(UUID id) {
    return db
        .query(
            "select i.*,r.name,r.status round_status,r.entry_deadline from round_invitation i join"
                + " app_round r on r.id=i.round_id where i.id=?",
            (rs, n) -> {
              String status = rs.getString("status");
              if (status.equals("PENDING")
                  && (!rs.getString("round_status").equals("OPEN")
                      || !clock.instant().isBefore(rs.getTimestamp("entry_deadline").toInstant())))
                status = "EXPIRED";
              return new Invitation(
                  id,
                  person(rs.getObject("sender_id", UUID.class)),
                  person(rs.getObject("recipient_id", UUID.class)),
                  rs.getObject("round_id", UUID.class),
                  rs.getString("name"),
                  status,
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getTimestamp("responded_at") == null
                      ? null
                      : rs.getTimestamp("responded_at").toInstant());
            },
            id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  @Transactional
  public Invitation invite(UUID sender, UUID recipient, UUID roundId) {
    var round = rounds.lock(roundId);
    if (!round.status().equals("OPEN") || !clock.instant().isBefore(round.entryDeadline()))
      throw ApiException.rule("ROUND_LOCKED", "Invitations require an open round");
    if (db.queryForObject(
            "select count(*) from friendship where user_id=? and friend_id=?",
            Long.class,
            sender,
            recipient)
        == 0) throw ApiException.rule("NOT_FRIENDS", "Invite someone from your friends list");
    UUID id = UUID.randomUUID();
    db.update(
        "insert into round_invitation values(?,?,?,?,'PENDING',?,null) on"
            + " conflict(sender_id,recipient_id,round_id) do nothing",
        id,
        sender,
        recipient,
        roundId,
        Timestamp.from(clock.instant()));
    return get(
        db.queryForObject(
            "select id from round_invitation where sender_id=? and recipient_id=? and round_id=?",
            UUID.class,
            sender,
            recipient,
            roundId));
  }

  @Transactional
  public Invitation respond(UUID viewer, UUID id, boolean accept) {
    var invite = get(id);
    if (!invite.recipient().id().equals(viewer))
      throw new ApiException(403, "FORBIDDEN", "Only the recipient can respond");
    // Same lock order as invitation creation and team mutations: round, then invitation.
    var round = rounds.lock(invite.roundId());
    db.queryForList("select id from round_invitation where id=? for update", id);
    invite = get(id);
    String wanted = accept ? "ACCEPTED" : "DECLINED";
    if (invite.status().equals(wanted)) return invite;
    if (!invite.status().equals("PENDING"))
      throw new ApiException(409, "INVITATION_CLOSED", "Invitation is no longer pending");
    if (accept) {
      if (!round.status().equals("OPEN") || !clock.instant().isBefore(round.entryDeadline()))
        throw ApiException.rule("ROUND_LOCKED", "Round has already started");
      if (teams.mineOrNull(round.id(), viewer) == null) {
        var user = users.byUsername(invite.recipient().username());
        var pick = teams.autoPick(round.id(), user, true);
        teams.create(
            round.id(),
            user,
            new TeamService.Lineup(
                pick.starters().stream().map(p -> p.id()).toList(),
                pick.bench().id(),
                pick.captainPlayerId()));
      }
    }
    db.update(
        "update round_invitation set status=?,responded_at=? where id=?",
        wanted,
        Timestamp.from(clock.instant()),
        id);
    return get(id);
  }
}
