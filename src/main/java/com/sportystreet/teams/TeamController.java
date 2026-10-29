package com.sportystreet.teams;

import com.sportystreet.shared.IdempotencyService;
import com.sportystreet.users.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/rounds/{roundId}", produces = "application/json")
public class TeamController {
  public record AutoPickRequest(boolean preferHomeHero) {}

  private final TeamService teams;
  private final IdempotencyService idempotency;

  public TeamController(TeamService teams, IdempotencyService idempotency) {
    this.teams = teams;
    this.idempotency = idempotency;
  }

  @PostMapping("/auto-pick")
  public TeamService.AutoPick autoPick(
      @PathVariable UUID roundId,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestBody AutoPickRequest request) {
    return teams.autoPick(roundId, user, request.preferHomeHero());
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "Created team (exact replay for the same idempotency key)",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = TeamService.TeamSnapshot.class)))
  @PostMapping("/teams")
  public ResponseEntity<String> create(
      @PathVariable UUID roundId,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TeamService.Lineup request) {
    return idempotency
        .execute(
            user.id(),
            key,
            "POST /api/v1/rounds/" + roundId + "/teams",
            request,
            201,
            () -> teams.create(roundId, user, request))
        .toHttp();
  }

  @GetMapping("/my-team")
  public TeamService.TeamSnapshot mine(
      @PathVariable UUID roundId, @AuthenticationPrincipal UserService.UserResponse user) {
    return teams.mine(roundId, user.id());
  }
}
