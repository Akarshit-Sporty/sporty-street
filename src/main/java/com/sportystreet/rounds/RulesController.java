package com.sportystreet.rounds;

import org.springframework.web.bind.annotation.*;

@RestController
public class RulesController {
  @GetMapping(value = "/api/v1/rules", produces = "application/json")
  public Rules rules() {
    return Rules.defaults();
  }
}
