package com.sportystreet.devtools;

import com.sportystreet.live.LiveController;
import com.sportystreet.rounds.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping(value = "/api/v1/dev", produces = "application/json")
public class DevGameController {
  public record StartRequest(@NotNull UUID roundId) {}

  private final RoundService rounds;
  private final RoundLifecycleService lifecycle;
  private final SimulatorService simulator;
  private final ResetService reset;
  private final LiveController live;

  public DevGameController(
      RoundService rounds,
      RoundLifecycleService lifecycle,
      SimulatorService simulator,
      ResetService reset,
      LiveController live) {
    this.rounds = rounds;
    this.lifecycle = lifecycle;
    this.simulator = simulator;
    this.reset = reset;
    this.live = live;
  }

  @PostMapping("/rounds/{id}/open")
  public RoundService.RoundResponse open(@PathVariable UUID id) {
    rounds.requireManual(id);
    return rounds.open(id);
  }

  @PostMapping("/rounds/{id}/start")
  public RoundService.RoundResponse start(@PathVariable UUID id) {
    rounds.requireManual(id);
    return lifecycle.start(id, true);
  }

  @PostMapping("/rounds/{id}/settle")
  public SettlementService.Summary settle(@PathVariable UUID id) {
    return simulator.settle(id);
  }

  @PostMapping("/simulator/start")
  public SimulatorService.Status start(@Valid @RequestBody StartRequest request) {
    return simulator.start(request.roundId());
  }

  @PostMapping("/simulator/stop")
  public SimulatorService.Status stop() {
    return simulator.stop();
  }

  @GetMapping("/simulator")
  public SimulatorService.Status status() {
    return simulator.status();
  }

  @PostMapping("/reset")
  public Map<String, Boolean> reset() {
    simulator.reset();
    live.closeAll();
    reset.reset();
    return Map.of("reset", true);
  }
}
