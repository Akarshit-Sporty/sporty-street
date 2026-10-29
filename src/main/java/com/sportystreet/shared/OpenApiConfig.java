package com.sportystreet.shared;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenApiCustomizer examplesAndErrors() {
    return api -> {
      var schemas = api.getComponents().getSchemas();
      schemas.putAll(ModelConverters.getInstance().read(ApiErrorResponse.class));
      schemas
          .get("ApiErrorResponse")
          .setExample(
              Map.of(
                  "code",
                  "INSUFFICIENT_RP",
                  "message",
                  "Not enough reward points",
                  "fieldErrors",
                  List.of(),
                  "traceId",
                  "00000000-0000-0000-0000-000000000001"));
      schemas.get("AutoPickRequest").setExample(Map.of("preferHomeHero", true));
      schemas.get("Wallet").setExample(Map.of("balanceRp", 150));
      var ids =
          java.util.stream.IntStream.rangeClosed(1, 6)
              .mapToObj(n -> String.format("00000000-0000-0000-0000-%012d", n))
              .toList();
      schemas
          .get("Lineup")
          .setExample(
              Map.of(
                  "starterPlayerIds",
                  ids.subList(0, 5),
                  "benchPlayerId",
                  ids.get(5),
                  "captainPlayerId",
                  ids.get(0)));
      schemas
          .get("Substitute")
          .setExample(
              Map.of(
                  "outgoingPlayerId",
                  ids.get(0),
                  "incomingPlayerId",
                  ids.get(5),
                  "expectedVersion",
                  0));
      schemas
          .get("MatchEventMessage")
          .setExample(
              Map.of(
                  "provider",
                  "simulator",
                  "providerEventId",
                  "goal-1",
                  "revision",
                  1,
                  "fixtureId",
                  ids.get(0),
                  "playerId",
                  ids.get(1),
                  "eventType",
                  "GOAL",
                  "matchMinute",
                  75,
                  "occurredAt",
                  "2026-10-27T12:02:30Z",
                  "voided",
                  false));
      api.getPaths()
          .values()
          .forEach(
              path ->
                  path.readOperations()
                      .forEach(
                          operation -> {
                            for (var code :
                                List.of("400", "401", "403", "404", "409", "422", "500")) {
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      code,
                                      new ApiResponse()
                                          .description(
                                              "Domain or request error; code is stable, traceId"
                                                  + " identifies the request.")
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/json",
                                                      new MediaType()
                                                          .schema(
                                                              new Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/ApiErrorResponse")))));
                            }
                          }));
    };
  }

  @Bean
  OpenAPI api() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Sporty Street demo API")
                .version("v1")
                .description(
                    "Local demo: use X-Dev-User: alice or POST /api/v1/dev/login?username=alice."
                        + " Rules are frozen per round."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "demoUser",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Dev-User"))
                .addSecuritySchemes(
                    "provider",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Provider-Token")))
        .addSecurityItem(new SecurityRequirement().addList("demoUser"));
  }
}
