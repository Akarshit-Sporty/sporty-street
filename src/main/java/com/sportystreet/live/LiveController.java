package com.sportystreet.live;

import com.sportystreet.users.UserService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LiveController {
  private record Client(UUID roundId, UUID userId, SseEmitter emitter) {}

  private final Set<Client> clients = ConcurrentHashMap.newKeySet();
  private final SnapshotService snapshots;

  public LiveController(SnapshotService snapshots) {
    this.snapshots = snapshots;
  }

  @GetMapping(value = "/api/v1/rounds/{roundId}/stream", produces = "text/event-stream")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description =
          "Named ROUND_SNAPSHOT events; each data field contains a complete envelope. Reconnect"
              + " returns current state.",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "text/event-stream",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = SnapshotService.Envelope.class)))
  public SseEmitter connect(
      @PathVariable UUID roundId, @AuthenticationPrincipal UserService.UserResponse user) {
    var initial = snapshots.get(roundId, user.id());
    var emitter = new SseEmitter(30 * 60 * 1000L);
    var client = new Client(roundId, user.id(), emitter);
    emitter.onCompletion(() -> clients.remove(client));
    emitter.onTimeout(
        () -> {
          clients.remove(client);
          emitter.complete();
        });
    emitter.onError(e -> clients.remove(client));
    synchronized (client) {
      try {
        emitter.send(SseEmitter.event().name("ROUND_SNAPSHOT").data(initial));
        clients.add(client);
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
    }
    return emitter;
  }

  @Scheduled(fixedDelay = 2000)
  public void publish() {
    for (var client : clients)
      synchronized (client) {
        try {
          client
              .emitter()
              .send(
                  SseEmitter.event()
                      .name("ROUND_SNAPSHOT")
                      .data(snapshots.get(client.roundId(), client.userId())));
        } catch (Exception e) {
          clients.remove(client);
          client.emitter().completeWithError(e);
        }
      }
  }

  @Scheduled(fixedDelay = 20000)
  public void heartbeat() {
    for (var client : clients)
      synchronized (client) {
        try {
          client.emitter().send(SseEmitter.event().comment("heartbeat"));
        } catch (Exception e) {
          clients.remove(client);
          client.emitter().completeWithError(e);
        }
      }
  }

  public void closeAll() {
    clients.forEach(c -> c.emitter().complete());
    clients.clear();
  }
}
