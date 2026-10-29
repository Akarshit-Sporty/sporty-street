package com.sportystreet.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/demo")
public class DemoController {
  private final DemoLoopService loop;

  public DemoController(DemoLoopService loop) {
    this.loop = loop;
  }

  @GetMapping("/session")
  public DemoLoopService.Session session() {
    return loop.session();
  }
}
