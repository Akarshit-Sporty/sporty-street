package com.sportystreet.ingestion;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "provider")
public class IngestionController {
  private final IngestionService ingestion;

  public IngestionController(IngestionService ingestion) {
    this.ingestion = ingestion;
  }

  @PostMapping(value = "/api/v1/internal/match-events", produces = "application/json")
  public IngestionService.Acceptance accept(
      @Valid @RequestBody IngestionService.MatchEventMessage request, Authentication auth) {
    return ingestion.accept(request, auth.getName());
  }
}
