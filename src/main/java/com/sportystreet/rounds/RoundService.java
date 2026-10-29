package com.sportystreet.rounds;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class RoundService {
  public record RoundResponse(
      UUID id,
      String name,
      String status,
      Instant entryDeadline,
      Instant subWindowOpensAt,
      Instant subWindowClosesAt,
      Rules rules,
      long stateVersion,
      Instant serverTime,
      List<CatalogueService.FixtureResponse> fixtures) {}

  private final RoundRepository rounds;
  private final CatalogueService catalogue;
  private final Clock clock;

  public RoundService(RoundRepository rounds, CatalogueService catalogue, Clock clock) {
    this.rounds = rounds;
    this.catalogue = catalogue;
    this.clock = clock;
  }

  public RoundResponse get(UUID id) {
    return response(rounds.findById(id).orElseThrow(ApiException::notFound));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public RoundResponse lock(UUID id) {
    return response(rounds.lock(id).orElseThrow(ApiException::notFound));
  }

  public PageResponse<RoundResponse> list(String status, int page, int size) {
    if (status != null && !Set.of("OPEN", "LIVE", "SETTLED").contains(status))
      throw new ApiException(400, "VALIDATION_FAILED", "Invalid round status");
    return PageResponse.of(
        rounds.findAll().stream()
            .filter(
                r ->
                    status == null
                        ? Set.of("OPEN", "LIVE").contains(r.status)
                        : r.status.equals(status))
            .sorted(
                Comparator.comparing((RoundEntity r) -> !r.status.equals("LIVE"))
                    .thenComparing(r -> r.entryDeadline)
                    .thenComparing(r -> r.id))
            .map(this::response)
            .toList(),
        page,
        size);
  }

  public List<UUID> due() {
    return rounds.findByStatusAndEntryDeadlineLessThanEqual("OPEN", clock.instant()).stream()
        .map(r -> r.id)
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long bump(UUID id) {
    var r = rounds.findById(id).orElseThrow(ApiException::notFound);
    return ++r.stateVersion;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public RoundResponse start(UUID id, boolean early) {
    var r = rounds.lock(id).orElseThrow(ApiException::notFound);
    if (r.status.equals("LIVE")) return response(r);
    if (!r.status.equals("OPEN")) throw ApiException.rule("ROUND_NOT_OPEN", "Round is not open");
    if (early && clock.instant().isBefore(r.entryDeadline)) r.entryDeadline = clock.instant();
    if (clock.instant().isBefore(r.entryDeadline))
      throw ApiException.rule("ROUND_NOT_LIVE", "Deadline has not arrived");
    r.status = "LIVE";
    r.subWindowOpensAt = r.entryDeadline;
    r.subWindowClosesAt = r.entryDeadline.plus(Duration.ofHours(6));
    r.stateVersion++;
    return response(r);
  }

  @Transactional
  public RoundResponse open(UUID id) {
    var r = rounds.lock(id).orElseThrow(ApiException::notFound);
    if (r.status.equals("OPEN")) return response(r);
    if (!r.status.equals("DRAFT"))
      throw ApiException.rule("ROUND_NOT_OPEN", "Only draft rounds can open");
    r.status = "OPEN";
    r.entryDeadline = clock.instant().plusSeconds(600);
    r.subWindowOpensAt = r.entryDeadline;
    r.subWindowClosesAt = r.entryDeadline.plus(Duration.ofHours(6));
    r.rules = Rules.defaults();
    r.stateVersion++;
    return response(r);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markSettled(UUID id) {
    var r = rounds.findById(id).orElseThrow(ApiException::notFound);
    r.status = "SETTLED";
    r.settledAt = clock.instant();
    r.stateVersion++;
  }

  private RoundResponse response(RoundEntity r) {
    return new RoundResponse(
        r.id,
        r.name,
        r.status,
        r.entryDeadline,
        r.subWindowOpensAt,
        r.subWindowClosesAt,
        r.rules,
        r.stateVersion,
        clock.instant(),
        catalogue.fixtures(r.id));
  }
}
