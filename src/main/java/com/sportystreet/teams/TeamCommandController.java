package com.sportystreet.teams;

import com.sportystreet.shared.IdempotencyService;
import com.sportystreet.users.UserService;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/teams/{id}", produces = "application/json")
public class TeamCommandController {
  private final TeamService teams;
  private final IdempotencyService idempotency;

  public TeamCommandController(TeamService teams, IdempotencyService idempotency) {
    this.teams = teams;
    this.idempotency = idempotency;
  }

  @PutMapping("/lineup")
  public TeamService.TeamSnapshot update(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @Valid @RequestBody TeamService.UpdateLineup request) {
    return teams.update(id, user.id(), request);
  }

  @PostMapping("/substitutions")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = TeamService.TeamSnapshot.class)))
  public ResponseEntity<String> substitute(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TeamService.Substitute request) {
    return idempotency
        .execute(
            user.id(),
            key,
            "POST /api/v1/teams/" + id + "/substitutions",
            request,
            200,
            () -> teams.substitute(id, user.id(), request))
        .toHttp();
  }
}
