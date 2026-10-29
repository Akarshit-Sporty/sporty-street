package com.sportystreet.profile;

import com.sportystreet.shared.PageResponse;
import com.sportystreet.users.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {
  private final ProfileService profiles;

  public ProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/me/profile")
  public ProfileService.Profile profile(@AuthenticationPrincipal UserService.UserResponse user) {
    return profiles.profile(user);
  }

  @GetMapping("/me/round-history")
  public PageResponse<ProfileService.History> history(
      @AuthenticationPrincipal UserService.UserResponse user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return profiles.history(user.id(), page, size);
  }

  @GetMapping("/leaderboard")
  public PageResponse<ProfileService.Ranking> leaderboard(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return PageResponse.of(profiles.rankings(), page, size);
  }
}
