package com.sportystreet.rounds;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.shared.PageResponse;
import com.sportystreet.users.UserService;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/rounds", produces = "application/json")
public class RoundController {
  private final RoundService rounds;
  private final CatalogueService catalogue;

  public RoundController(RoundService rounds, CatalogueService catalogue) {
    this.rounds = rounds;
    this.catalogue = catalogue;
  }

  @GetMapping
  public PageResponse<RoundService.RoundResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String format,
      @RequestParam(defaultValue = "UTC") String timeZone,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return rounds.list(status, period, format, timeZone, page, size);
  }

  @GetMapping("/{id}")
  public RoundService.RoundResponse get(@PathVariable UUID id) {
    return rounds.get(id);
  }

  @GetMapping("/{id}/players")
  public PageResponse<CatalogueService.PlayerResponse> players(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(required = false) String position,
      @RequestParam(required = false) UUID countryId,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    rounds.get(id);
    return PageResponse.of(
        catalogue.players(id, user.countryId()).stream()
            .filter(p -> position == null || p.position().equals(position))
            .filter(p -> countryId == null || p.countryId().equals(countryId))
            .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))
            .toList(),
        page,
        size);
  }

  @GetMapping("/{id}/players/{playerId}")
  public CatalogueService.PlayerResponse player(
      @PathVariable UUID id,
      @PathVariable UUID playerId,
      @AuthenticationPrincipal UserService.UserResponse user) {
    rounds.get(id);
    return catalogue.player(id, playerId, user.countryId());
  }
}
