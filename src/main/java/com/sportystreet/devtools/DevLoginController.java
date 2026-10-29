package com.sportystreet.devtools;

import com.sportystreet.users.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
public class DevLoginController {
  private final UserService users;

  public DevLoginController(UserService users) {
    this.users = users;
  }

  @PostMapping(value = "/api/v1/dev/login", produces = "application/json")
  @SecurityRequirements
  public ResponseEntity<UserService.UserResponse> login(@RequestParam String username) {
    var user = users.byUsername(username);
    var cookie =
        ResponseCookie.from("dev_user", user.username())
            .httpOnly(true)
            .sameSite("Lax")
            .path("/api")
            .build();
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(user);
  }
}
