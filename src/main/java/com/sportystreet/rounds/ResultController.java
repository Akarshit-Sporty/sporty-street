package com.sportystreet.rounds;

import com.sportystreet.users.UserService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResultController {
  private final ResultService results;

  public ResultController(ResultService results) {
    this.results = results;
  }

  @GetMapping(value = "/api/v1/rounds/{id}/result", produces = "application/json")
  public ResultService.Result mine(
      @PathVariable UUID id, @AuthenticationPrincipal UserService.UserResponse user) {
    return results.mine(id, user.id());
  }
}
