package com.sportystreet.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;

/** The advisory lock, operation and response are committed together in the caller's transaction. */
@Service
public class IdempotencyService {
  public record StoredResponse(int status, String body) {
    public ResponseEntity<String> toHttp() {
      return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
  }

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public IdempotencyService(JdbcTemplate db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public StoredResponse execute(
      UUID userId,
      String key,
      String methodPath,
      Object request,
      int status,
      Supplier<?> operation) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new ApiException(
          400, "VALIDATION_FAILED", "Idempotency-Key must contain 1–200 characters");
    String hash = hash(json.writeValueAsString(canonical(json.valueToTree(request))));
    // Serialize even when a record does not exist yet. Hash collisions only cause extra waiting.
    db.queryForList(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        userId + ":" + methodPath + ":" + key);
    var existing =
        db.query(
            "select request_hash, response_status, response_body from idempotency_record where"
                + " user_id=? and key=? and method_path=?",
            (rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getString(3)},
            userId,
            key,
            methodPath);
    if (!existing.isEmpty()) {
      var row = existing.getFirst();
      if (!hash.equals(row[0]))
        throw ApiException.rule(
            "IDEMPOTENCY_KEY_REUSED", "Key already used for a different request");
      return new StoredResponse((int) row[1], (String) row[2]);
    }
    String body = json.writeValueAsString(operation.get());
    db.update(
        "insert into idempotency_record values (?, ?, ?, ?, ?, ?, ?)",
        userId,
        key,
        methodPath,
        hash,
        status,
        body,
        java.sql.Timestamp.from(clock.instant()));
    return new StoredResponse(status, body);
  }

  private Object canonical(JsonNode node) {
    if (node.isObject()) {
      var sorted = new TreeMap<String, Object>();
      node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      var values = new ArrayList<>();
      node.forEach(n -> values.add(canonical(n)));
      return values;
    }
    return node;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
