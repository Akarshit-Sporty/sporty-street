package com.sportystreet.ingestion;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.rounds.RoundService;
import com.sportystreet.scoring.*;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IngestionService {
  public record MatchEventMessage(
      @NotBlank @Size(max = 50) String provider,
      @NotBlank @Size(max = 200) String providerEventId,
      @Min(1) int revision,
      @NotNull UUID fixtureId,
      @NotNull UUID playerId,
      @NotNull ScoringEngine.EventType eventType,
      @Min(0) int matchMinute,
      @NotNull Instant occurredAt,
      boolean voided) {}

  public record Acceptance(UUID eventId, boolean applied, long stateVersion) {}

  private final JdbcTemplate db;
  private final RoundService rounds;
  private final CatalogueService catalogue;
  private final TeamService teams;
  private final ScoreService scores;
  private final ObjectMapper json;
  private final Clock clock;

  public IngestionService(
      JdbcTemplate db,
      RoundService rounds,
      CatalogueService catalogue,
      TeamService teams,
      ScoreService scores,
      ObjectMapper json,
      Clock clock) {
    this.db = db;
    this.rounds = rounds;
    this.catalogue = catalogue;
    this.teams = teams;
    this.scores = scores;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public Acceptance accept(MatchEventMessage request, String authenticatedProvider) {
    if (!request.provider().equals(authenticatedProvider))
      throw new ApiException(403, "FORBIDDEN", "Token does not match provider");
    db.queryForList(
        "select pg_advisory_xact_lock(hashtextextended(?,1))",
        request.provider() + ":" + request.providerEventId());
    var prior =
        db.query(
            "select fixture_id,revision,request_hash,acceptance_response from match_event where"
                + " provider=? and provider_event_id=? order by revision desc",
            (rs, n) ->
                new Prior(
                    rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4)),
            request.provider(),
            request.providerEventId());
    String hash = IdempotencyService.hash(json.writeValueAsString(request));
    for (var event : prior)
      if (event.revision() == request.revision()) {
        if (!event.hash().equals(hash))
          throw new ApiException(
              409, "EVENT_REVISION_CONFLICT", "Event revision already has a different payload");
        return json.readValue(event.response(), Acceptance.class);
      }
    if (!prior.isEmpty() && !prior.getFirst().fixtureId().equals(request.fixtureId()))
      throw ApiException.rule("INVALID_EVENT", "Corrections cannot change fixture");
    var fixture = catalogue.fixture(request.fixtureId());
    if (!fixture.provider().equals(authenticatedProvider))
      throw ApiException.rule("INVALID_EVENT", "Fixture belongs to another provider");
    var round = rounds.lock(fixture.roundId());
    if (round.status().equals("SETTLED"))
      throw ApiException.rule("ROUND_SETTLED", "Round has settled");
    if (!round.status().equals("LIVE"))
      throw ApiException.rule("ROUND_NOT_LIVE", "Start the round before submitting events");
    if (request.occurredAt().isBefore(round.entryDeadline())
        || request.occurredAt().isAfter(clock.instant()))
      throw ApiException.rule(
          "INVALID_EVENT", "Occurrence time must be between round start and now");
    var player =
        catalogue.players(round.id(), null).stream()
            .filter(p -> p.id().equals(request.playerId()))
            .findFirst()
            .orElseThrow(() -> ApiException.rule("UNKNOWN_PLAYER", "Player is not in this round"));
    if (!player.fixtureId().equals(request.fixtureId()))
      throw ApiException.rule("INVALID_EVENT", "Player does not belong to this fixture");
    boolean applied = prior.isEmpty() || request.revision() > prior.getFirst().revision();
    long version = applied ? rounds.bump(round.id()) : round.stateVersion();
    var acceptance = new Acceptance(UUID.randomUUID(), applied, version);
    db.update(
        "insert into match_event values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        acceptance.eventId(),
        request.provider(),
        request.providerEventId(),
        request.revision(),
        request.fixtureId(),
        request.playerId(),
        request.eventType().name(),
        request.matchMinute(),
        Timestamp.from(request.occurredAt()),
        request.voided(),
        hash,
        Timestamp.from(clock.instant()),
        json.writeValueAsString(acceptance));
    if (applied)
      scores.recalculate(
          round.id(), round.rules(), latestEvents(round.id()), teams.scoringContexts(round.id()));
    return acceptance;
  }

  private record Prior(UUID fixtureId, int revision, String hash, String response) {}

  private List<ScoringEngine.Event> latestEvents(UUID roundId) {
    var events =
        db.query(
            """
select e.* from match_event e join fixture f on f.id=e.fixture_id
where f.round_id=? and not e.voided and not exists (
  select 1 from match_event newer where newer.provider=e.provider and newer.provider_event_id=e.provider_event_id and newer.revision>e.revision)
order by e.occurred_at,e.provider,e.provider_event_id
""",
            (rs, n) ->
                new ScoringEngine.Event(
                    rs.getObject("id", UUID.class),
                    rs.getObject("fixture_id", UUID.class),
                    rs.getObject("player_id", UUID.class),
                    ScoringEngine.EventType.valueOf(rs.getString("event_type")),
                    rs.getInt("match_minute"),
                    rs.getTimestamp("occurred_at").toInstant()),
            roundId);
    var appearances = new HashSet<String>();
    return events.stream()
        .filter(
            e ->
                e.type() != ScoringEngine.EventType.APPEARANCE
                    || appearances.add(e.fixtureId() + ":" + e.playerId()))
        .toList();
  }
}
