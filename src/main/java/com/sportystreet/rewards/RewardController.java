package com.sportystreet.rewards;

import com.sportystreet.shared.*;
import com.sportystreet.users.UserService;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
public class RewardController {
  private final RewardService rewards;
  private final IdempotencyService idempotency;

  public RewardController(RewardService rewards, IdempotencyService idempotency) {
    this.rewards = rewards;
    this.idempotency = idempotency;
  }

  @GetMapping("/rewards/catalogue")
  public RewardService.Catalogue catalogue(@AuthenticationPrincipal UserService.UserResponse user) {
    return rewards.catalogue(user.id());
  }

  @GetMapping("/rewards/wallet")
  public RewardService.Wallet wallet(@AuthenticationPrincipal UserService.UserResponse user) {
    return rewards.wallet(user.id());
  }

  @GetMapping("/rewards/history")
  public PageResponse<RewardService.History> history(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return rewards.history(user.id(), page, size);
  }

  @GetMapping("/gifts")
  public PageResponse<RewardService.Gift> gifts(
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return rewards.gifts(category, page, size);
  }

  @GetMapping("/me/purchases")
  public PageResponse<RewardService.Purchase> purchases(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return rewards.purchases(user.id(), page, size);
  }

  @PostMapping("/gifts/{id}/purchases")
  @ApiResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = RewardService.Purchased.class)))
  public ResponseEntity<String> purchase(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody(required = false) Map<String, Object> body) {
    if (body != null && !body.isEmpty())
      throw new ApiException(400, "VALIDATION_FAILED", "Purchase takes an empty body");
    return idempotency
        .execute(
            user.id(),
            key,
            "POST /api/v1/gifts/" + id + "/purchases",
            Map.of(),
            201,
            () -> rewards.purchase(user.id(), id))
        .toHttp();
  }
}
