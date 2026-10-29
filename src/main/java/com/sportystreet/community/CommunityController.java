package com.sportystreet.community;

import com.sportystreet.users.UserService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping(value = "/api/v1", produces = "application/json")
public class CommunityController {
  private final CommunityService community;

  public CommunityController(CommunityService community) {
    this.community = community;
  }

  @GetMapping("/leagues")
  public CommunityService.CommunityPage<CommunityService.LeagueCard> leagues(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "MINE") String scope,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return community.leagues(user.id(), scope, page, size);
  }

  @GetMapping("/leagues/{id}")
  public CommunityService.LeagueDetail detail(
      @PathVariable UUID id, @AuthenticationPrincipal UserService.UserResponse user) {
    return community.detail(id, user.id());
  }

  @GetMapping("/leagues/{id}/table")
  public CommunityService.CommunityPage<CommunityService.LeagueMember> table(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return community.table(id, user.id(), page, size);
  }

  @GetMapping("/leagues/{id}/rivalries")
  public CommunityService.Rivalries rivalries(
      @PathVariable UUID id, @AuthenticationPrincipal UserService.UserResponse user) {
    return community.rivalries(id, user.id());
  }

  @GetMapping("/leagues/{id}/banter")
  public CommunityService.Banter banter(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return community.banter(id, user.id(), page, size);
  }

  @GetMapping("/backing/managers")
  public CommunityService.Managers managers(
      @RequestParam UUID roundId,
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "ALL") String filter,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return community.managers(roundId, user.id(), filter, page, size);
  }
}
