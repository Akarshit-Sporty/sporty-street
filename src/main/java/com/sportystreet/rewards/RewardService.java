package com.sportystreet.rewards;

import com.sportystreet.rounds.Rules;
import com.sportystreet.scoring.ScoreService;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class RewardService {
  public record Wallet(int balanceRp) {}

  public record Gift(
      UUID id, String name, String description, String category, String imageUrl, int priceRp) {}

  public record Purchase(
      UUID id, UUID giftId, String giftName, int priceRp, String voucherCode, Instant createdAt) {}

  public record Purchased(Purchase purchase, int balanceRp) {}

  public record History(
      UUID id, int delta, String reason, UUID roundId, UUID purchaseId, Instant createdAt) {}

  private final JdbcTemplate db;
  private final Clock clock;

  public RewardService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void award(
      UUID roundId,
      Rules.Rewards rules,
      List<TeamService.Participant> participants,
      List<ScoreService.Entry> standings) {
    Map<UUID, Long> ranks = new HashMap<>();
    standings.forEach(e -> ranks.put(e.teamId(), e.rank()));
    // Participant order is ascending user ID, shared by every settlement's wallet locks.
    for (var participant : participants) {
      lockWallet(participant.userId());
      credit(participant.userId(), roundId, "PARTICIPATION", rules.participationRp());
      if (ranks.get(participant.teamId()) == 1L)
        credit(participant.userId(), roundId, "WINNER", rules.winnerBonusRp());
    }
  }

  private void credit(UUID user, UUID round, String reason, int amount) {
    int inserted =
        db.update(
            "insert into reward_ledger values (?,?,?,?,?,null,?) on conflict"
                + " (user_id,round_id,reason) do nothing",
            UUID.randomUUID(),
            user,
            amount,
            reason,
            round,
            Timestamp.from(clock.instant()));
    if (inserted == 1)
      db.update("update reward_wallet set balance=balance+? where user_id=?", amount, user);
  }

  public Wallet wallet(UUID user) {
    return db
        .query(
            "select balance from reward_wallet where user_id=?",
            (rs, n) -> new Wallet(rs.getInt(1)),
            user)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  private int lockWallet(UUID user) {
    return db
        .query(
            "select balance from reward_wallet where user_id=? for update",
            (rs, n) -> rs.getInt(1),
            user)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  public PageResponse<Gift> gifts(String category, int page, int size) {
    var all = db.query("select * from gift where active order by price_rp,id", (rs, n) -> gift(rs));
    return PageResponse.of(
        all.stream().filter(g -> category == null || category.equals(g.category())).toList(),
        page,
        size);
  }

  @Transactional
  public Purchased purchase(UUID user, UUID giftId) {
    var gift =
        db.query("select * from gift where id=? and active", (rs, n) -> gift(rs), giftId).stream()
            .findFirst()
            .orElseThrow(() -> ApiException.rule("GIFT_UNAVAILABLE", "Gift is unavailable"));
    int balance = lockWallet(user);
    if (balance < gift.priceRp())
      throw ApiException.rule("INSUFFICIENT_RP", "Not enough reward points");
    UUID id = UUID.randomUUID();
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var purchase =
        new Purchase(id, gift.id(), gift.name(), gift.priceRp(), "SST-" + UUID.randomUUID(), now);
    db.update("update reward_wallet set balance=balance-? where user_id=?", gift.priceRp(), user);
    db.update(
        "insert into gift_purchase values (?,?,?,?,?,?,?)",
        id,
        user,
        gift.id(),
        gift.name(),
        gift.priceRp(),
        purchase.voucherCode(),
        Timestamp.from(now));
    db.update(
        "insert into reward_ledger values (?,?,?,'PURCHASE',null,?,?)",
        UUID.randomUUID(),
        user,
        -gift.priceRp(),
        id,
        Timestamp.from(now));
    return new Purchased(purchase, balance - gift.priceRp());
  }

  public PageResponse<Purchase> purchases(UUID user, int page, int size) {
    return PageResponse.of(
        db.query(
            "select * from gift_purchase where user_id=? order by created_at desc,id",
            (rs, n) ->
                new Purchase(
                    rs.getObject("id", UUID.class),
                    rs.getObject("gift_id", UUID.class),
                    rs.getString("gift_name"),
                    rs.getInt("price_rp"),
                    rs.getString("voucher_code"),
                    rs.getTimestamp("created_at").toInstant()),
            user),
        page,
        size);
  }

  public PageResponse<History> history(UUID user, int page, int size) {
    return PageResponse.of(
        db.query(
            "select * from reward_ledger where user_id=? order by created_at desc,id",
            (rs, n) ->
                new History(
                    rs.getObject("id", UUID.class),
                    rs.getInt("delta"),
                    rs.getString("reason"),
                    rs.getObject("round_id", UUID.class),
                    rs.getObject("purchase_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()),
            user),
        page,
        size);
  }

  private Gift gift(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Gift(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("category"),
        rs.getString("image_url"),
        rs.getInt("price_rp"));
  }
}
