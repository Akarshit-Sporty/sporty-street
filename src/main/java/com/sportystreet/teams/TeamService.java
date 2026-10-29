package com.sportystreet.teams;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.rounds.RoundService;
import com.sportystreet.scoring.*;
import com.sportystreet.shared.*;
import com.sportystreet.users.UserService;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class TeamService {
  public record Lineup(
      @NotNull List<@NotNull UUID> starterPlayerIds,
      @NotNull UUID benchPlayerId,
      @NotNull UUID captainPlayerId) {}

  public record AutoPick(
      List<CatalogueService.PlayerResponse> starters,
      CatalogueService.PlayerResponse bench,
      UUID captainPlayerId,
      String explanation) {}

  public record PlayerState(CatalogueService.PlayerResponse player, int points) {}

  public record TeamSnapshot(
      UUID id,
      UUID roundId,
      List<PlayerState> starters,
      PlayerState bench,
      UUID captainPlayerId,
      UUID homeCountryId,
      UUID homeHeroPlayerId,
      PendingSubstitution pendingSubstitution,
      int subsUsed,
      int subsRemaining,
      int score,
      long rank,
      long version,
      long stateVersion,
      Instant asOf) {}

  public record UpdateLineup(
      @NotNull List<@NotNull UUID> starterPlayerIds,
      @NotNull UUID benchPlayerId,
      @NotNull UUID captainPlayerId,
      @NotNull @Min(0) Long expectedVersion) {}

  public record Substitute(
      @NotNull UUID outgoingPlayerId,
      @NotNull UUID incomingPlayerId,
      @NotNull @Min(0) Long expectedVersion) {}

  public record PendingSubstitution(
      UUID outgoingPlayerId, UUID incomingPlayerId, Instant effectiveAt) {}

  public record Participant(UUID teamId, UUID userId) {}

  private record Team(
      UUID id, UUID userId, UUID roundId, UUID captain, UUID country, int subs, long version) {}

  private final JdbcTemplate db;
  private final RoundService rounds;
  private final CatalogueService catalogue;
  private final ScoreService scores;
  private final Clock clock;

  public TeamService(
      JdbcTemplate db,
      RoundService rounds,
      CatalogueService catalogue,
      ScoreService scores,
      Clock clock) {
    this.db = db;
    this.rounds = rounds;
    this.catalogue = catalogue;
    this.scores = scores;
    this.clock = clock;
  }

  public AutoPick autoPick(UUID roundId, UserService.UserResponse user, boolean preferHome) {
    rounds.get(roundId);
    var pool =
        new ArrayList<>(
            catalogue.players(roundId, user.countryId()).stream()
                .filter(CatalogueService.PlayerResponse::eligible)
                .toList());
    if (preferHome) pool.sort(Comparator.comparing(p -> !p.isHomeCountry()));
    var countries = new HashSet<UUID>();
    var starters = new ArrayList<CatalogueService.PlayerResponse>();
    for (var player : pool)
      if (starters.size() < 5 && countries.add(player.countryId())) starters.add(player);
    var chosen = starters.stream().map(CatalogueService.PlayerResponse::id).toList();
    var bench = pool.stream().filter(p -> !chosen.contains(p.id())).findFirst();
    if (starters.size() != 5 || bench.isEmpty())
      throw ApiException.rule(
          "AUTO_PICK_IMPOSSIBLE", "Need five starter countries and a sixth player");
    return new AutoPick(
        starters,
        bench.get(),
        starters.getFirst().id(),
        "Five different countries; first starter is captain; home player prioritized when"
            + " requested.");
  }

  @Transactional
  public TeamSnapshot create(UUID roundId, UserService.UserResponse user, Lineup lineup) {
    var round = rounds.lock(roundId);
    if (!round.status().equals("OPEN"))
      throw ApiException.rule("ROUND_NOT_OPEN", "Round is not open");
    if (!clock.instant().isBefore(round.entryDeadline()))
      throw ApiException.rule("ROUND_LOCKED", "Entry deadline has passed");
    if (find(roundId, user.id()).isPresent())
      throw new ApiException(409, "TEAM_ALREADY_EXISTS", "One team per round");
    validate(roundId, lineup);
    UUID id = UUID.randomUUID();
    db.update(
        "insert into team values (?,?,?,?,?,0,0)",
        id,
        user.id(),
        roundId,
        lineup.captainPlayerId(),
        user.countryId());
    for (UUID player : lineup.starterPlayerIds())
      selection(id, player, "STARTER", round.entryDeadline());
    selection(id, lineup.benchPlayerId(), "BENCH", round.entryDeadline());
    scores.initialize(roundId, id);
    rounds.bump(roundId);
    return snapshot(team(id), rounds.get(roundId));
  }

  @Transactional
  public TeamSnapshot update(UUID id, UUID userId, UpdateLineup request) {
    requireOwner(id, userId);
    var round = rounds.lock(team(id).roundId());
    var t = team(id);
    checkVersion(t, request.expectedVersion());
    if (!round.status().equals("OPEN") || !clock.instant().isBefore(round.entryDeadline()))
      throw ApiException.rule("ROUND_LOCKED", "Lineup is locked");
    var lineup =
        new Lineup(request.starterPlayerIds(), request.benchPlayerId(), request.captainPlayerId());
    validate(round.id(), lineup);
    db.update("delete from team_selection where team_id=?", id);
    for (var player : lineup.starterPlayerIds())
      selection(id, player, "STARTER", round.entryDeadline());
    selection(id, lineup.benchPlayerId(), "BENCH", round.entryDeadline());
    db.update(
        "update team set captain_player_id=?,version=version+1 where id=?",
        lineup.captainPlayerId(),
        id);
    rounds.bump(round.id());
    return snapshot(team(id), rounds.get(round.id()));
  }

  @Transactional
  public TeamSnapshot substitute(UUID id, UUID userId, Substitute request) {
    requireOwner(id, userId);
    var round = rounds.lock(team(id).roundId());
    var t = team(id);
    checkVersion(t, request.expectedVersion());
    if (!round.status().equals("LIVE"))
      throw ApiException.rule("ROUND_NOT_LIVE", "Round is not live");
    if (t.subs() > 0) throw ApiException.rule("SUBSTITUTION_USED", "One substitution per round");
    var now = clock.instant();
    if (now.isBefore(round.subWindowOpensAt()) || !now.isBefore(round.subWindowClosesAt()))
      throw ApiException.rule("SUBSTITUTION_WINDOW_CLOSED", "Substitution window is closed");
    var current = snapshot(t, round);
    if (current.bench() == null
        || !current.bench().player().id().equals(request.incomingPlayerId())
        || current.starters().stream()
            .noneMatch(p -> p.player().id().equals(request.outgoingPlayerId())))
      throw ApiException.rule(
          "INVALID_SUBSTITUTION", "Swap one active starter for the bench player");
    if (!current.bench().player().eligible())
      throw ApiException.rule("PLAYER_INELIGIBLE", "Bench player is ineligible");
    var countries = new HashSet<UUID>();
    for (var player : current.starters())
      if (!player.player().id().equals(request.outgoingPlayerId()))
        countries.add(player.player().countryId());
    if (!countries.add(current.bench().player().countryId()))
      throw ApiException.rule("DUPLICATE_COUNTRY", "Swap would duplicate a starter country");
    var accepted = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var cutoff = accepted.plusSeconds(round.rules().subActivationDelaySeconds());
    db.update(
        "update team_selection set effective_to=? where team_id=? and player_id in (?,?) and"
            + " effective_to is null",
        Timestamp.from(cutoff),
        id,
        request.outgoingPlayerId(),
        request.incomingPlayerId());
    selection(id, request.outgoingPlayerId(), "BENCH", cutoff);
    selection(id, request.incomingPlayerId(), "STARTER", cutoff);
    db.update(
        "insert into team_substitution values (?,?,?,?,?)",
        id,
        request.outgoingPlayerId(),
        request.incomingPlayerId(),
        Timestamp.from(accepted),
        Timestamp.from(cutoff));
    db.update("update team set subs_used=1,version=version+1 where id=?", id);
    rounds.bump(round.id());
    return snapshot(team(id), rounds.get(round.id()));
  }

  private void checkVersion(Team team, long expected) {
    if (team.version() != expected)
      throw new ApiException(409, "VERSION_CONFLICT", "Reload the current team version");
  }

  public List<Participant> participants(UUID roundId) {
    return db.query(
        "select id,user_id from team where round_id=? order by user_id",
        (rs, n) -> new Participant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        roundId);
  }

  public List<PendingSubstitution> substitutions(UUID roundId) {
    return db.query(
        "select s.* from team_substitution s join team t on t.id=s.team_id where t.round_id=? order"
            + " by s.team_id",
        (rs, n) ->
            new PendingSubstitution(
                rs.getObject("outgoing_player_id", UUID.class),
                rs.getObject("incoming_player_id", UUID.class),
                rs.getTimestamp("effective_at").toInstant()),
        roundId);
  }

  public boolean hasPendingSubstitution(UUID roundId) {
    return substitutions(roundId).stream().anyMatch(s -> clock.instant().isBefore(s.effectiveAt()));
  }

  private PendingSubstitution pending(UUID teamId, Instant now) {
    return db
        .query(
            "select * from team_substitution where team_id=? and effective_at>?",
            (rs, n) ->
                new PendingSubstitution(
                    rs.getObject("outgoing_player_id", UUID.class),
                    rs.getObject("incoming_player_id", UUID.class),
                    rs.getTimestamp("effective_at").toInstant()),
            teamId,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void validate(UUID roundId, Lineup lineup) {
    if (lineup.starterPlayerIds() == null || lineup.starterPlayerIds().size() != 5)
      throw ApiException.rule("WRONG_SQUAD_SIZE", "Exactly five starters required");
    var ids = new ArrayList<>(lineup.starterPlayerIds());
    ids.add(lineup.benchPlayerId());
    if (new HashSet<>(ids).size() != 6)
      throw ApiException.rule("DUPLICATE_PLAYER", "Six distinct players required");
    var pool = catalogue.players(roundId, null);
    var selected = pool.stream().filter(p -> ids.contains(p.id()) && p.eligible()).toList();
    if (selected.size() != 6)
      throw ApiException.rule("PLAYER_INELIGIBLE", "All players must be eligible for this round");
    if (!lineup.starterPlayerIds().contains(lineup.captainPlayerId()))
      throw ApiException.rule("CAPTAIN_NOT_STARTER", "Captain must be a starter");
    if (selected.stream()
            .filter(p -> lineup.starterPlayerIds().contains(p.id()))
            .map(CatalogueService.PlayerResponse::countryId)
            .distinct()
            .count()
        != 5)
      throw ApiException.rule("DUPLICATE_COUNTRY", "Starters must represent five countries");
  }

  private void selection(UUID team, UUID player, String role, Instant from) {
    db.update(
        "insert into team_selection values (?,?,?,?,?,null)",
        UUID.randomUUID(),
        team,
        player,
        role,
        Timestamp.from(from));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void activate(UUID roundId, Instant deadline) {
    db.update(
        "update team_selection set effective_from=? where team_id in (select id from team where"
            + " round_id=?)",
        Timestamp.from(deadline),
        roundId);
  }

  public TeamSnapshot mine(UUID roundId, UUID userId) {
    return snapshot(find(roundId, userId).orElseThrow(ApiException::notFound), rounds.get(roundId));
  }

  public TeamSnapshot mineOrNull(UUID roundId, UUID userId) {
    return find(roundId, userId).map(t -> snapshot(t, rounds.get(roundId))).orElse(null);
  }

  public void requireOwner(UUID teamId, UUID userId) {
    if (!team(teamId).userId().equals(userId))
      throw new ApiException(403, "FORBIDDEN", "Team belongs to another user");
  }

  public List<ScoringEngine.TeamContext> scoringContexts(UUID roundId) {
    return db
        .query("select * from team where round_id=? order by id", (rs, n) -> map(rs), roundId)
        .stream()
        .map(
            t ->
                new ScoringEngine.TeamContext(t.id(), t.captain(), t.country(), selections(t.id())))
        .toList();
  }

  private List<ScoringEngine.Selection> selections(UUID teamId) {
    return db.query(
        "select s.*,p.country_id from team_selection s join player p on p.id=s.player_id where"
            + " team_id=? order by s.id",
        (rs, n) ->
            new ScoringEngine.Selection(
                rs.getObject("player_id", UUID.class),
                rs.getObject("country_id", UUID.class),
                rs.getString("role"),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_to") == null
                    ? null
                    : rs.getTimestamp("effective_to").toInstant()),
        teamId);
  }

  private TeamSnapshot snapshot(Team t, RoundService.RoundResponse round) {
    var now = clock.instant();
    var pool = catalogue.players(t.roundId(), t.country());
    var selections = selections(t.id());
    var breakdown = scores.breakdown(t.id());
    Map<UUID, Integer> points = new HashMap<>();
    breakdown.players().forEach(p -> points.put(p.playerId(), p.total()));
    var current =
        selections.stream()
            .filter(
                s ->
                    round.status().equals("OPEN")
                        || (!now.isBefore(s.effectiveFrom())
                            && (s.effectiveTo() == null || now.isBefore(s.effectiveTo()))))
            .toList();
    var starters =
        current.stream()
            .filter(s -> s.role().equals("STARTER"))
            .map(s -> state(pool, s.playerId(), points))
            .toList();
    var bench =
        current.stream()
            .filter(s -> s.role().equals("BENCH"))
            .map(s -> state(pool, s.playerId(), points))
            .findFirst()
            .orElse(null);
    var hero =
        starters.stream()
            .map(PlayerState::player)
            .filter(CatalogueService.PlayerResponse::isHomeCountry)
            .map(CatalogueService.PlayerResponse::id)
            .findFirst()
            .orElse(null);
    long rank =
        scores.standings(t.roundId()).stream()
            .filter(e -> e.teamId().equals(t.id()))
            .mapToLong(ScoreService.Entry::rank)
            .findFirst()
            .orElse(0);
    return new TeamSnapshot(
        t.id(),
        t.roundId(),
        starters,
        bench,
        t.captain(),
        t.country(),
        hero,
        pending(t.id(), now),
        t.subs(),
        1 - t.subs(),
        breakdown.total(),
        rank,
        t.version(),
        round.stateVersion(),
        now);
  }

  private PlayerState state(
      List<CatalogueService.PlayerResponse> pool, UUID player, Map<UUID, Integer> points) {
    return new PlayerState(
        pool.stream().filter(p -> p.id().equals(player)).findFirst().orElseThrow(),
        points.getOrDefault(player, 0));
  }

  private Optional<Team> find(UUID roundId, UUID userId) {
    return db
        .query(
            "select * from team where round_id=? and user_id=?",
            (rs, n) -> map(rs),
            roundId,
            userId)
        .stream()
        .findFirst();
  }

  private Team team(UUID id) {
    return db.query("select * from team where id=?", (rs, n) -> map(rs), id).stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  private Team map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Team(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("round_id", UUID.class),
        rs.getObject("captain_player_id", UUID.class),
        rs.getObject("home_country_id", UUID.class),
        rs.getInt("subs_used"),
        rs.getLong("version"));
  }
}
