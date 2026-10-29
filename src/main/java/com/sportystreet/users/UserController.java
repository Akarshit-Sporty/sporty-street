package com.sportystreet.users;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
public class UserController {
  @GetMapping("/me")
  public UserService.UserResponse me(@AuthenticationPrincipal UserService.UserResponse user) {
    return user;
  }
}
