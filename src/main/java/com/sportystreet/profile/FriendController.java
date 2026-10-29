package com.sportystreet.profile;

import com.sportystreet.shared.*;
import com.sportystreet.users.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class FriendController {
  public record Invite(@NotNull UUID roundId) {}

  private final FriendService friends;
  private final IdempotencyService idempotency;

  public FriendController(FriendService friends, IdempotencyService idempotency) {
    this.friends = friends;
    this.idempotency = idempotency;
  }

  @GetMapping("/me/friends")
  public PageResponse<FriendService.Friend> friends(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return friends.friends(user.id(), page, size);
  }

  @GetMapping("/me/invitations")
  public PageResponse<FriendService.Invitation> invitations(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return friends.invitations(user.id(), page, size);
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = FriendService.Invitation.class)))
  @PostMapping("/friends/{friendId}/invitations")
  public ResponseEntity<String> invite(
      @AuthenticationPrincipal UserService.UserResponse user,
      @PathVariable UUID friendId,
      @Valid @RequestBody Invite body,
      @RequestHeader("Idempotency-Key") String key) {
    return idempotency
        .execute(
            user.id(),
            key,
            "POST /api/v1/friends/" + friendId + "/invitations",
            body,
            201,
            () -> friends.invite(user.id(), friendId, body.roundId()))
        .toHttp();
  }

  @PostMapping("/invitations/{id}/accept")
  public FriendService.Invitation accept(
      @AuthenticationPrincipal UserService.UserResponse user, @PathVariable UUID id) {
    return friends.respond(user.id(), id, true);
  }

  @PostMapping("/invitations/{id}/decline")
  public FriendService.Invitation decline(
      @AuthenticationPrincipal UserService.UserResponse user, @PathVariable UUID id) {
    return friends.respond(user.id(), id, false);
  }
}
