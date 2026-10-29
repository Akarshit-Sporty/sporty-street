package com.sportystreet.community;

import com.sportystreet.catalogue.CatalogueService;
import com.sportystreet.rounds.RoundService;
import com.sportystreet.shared.*;
import com.sportystreet.teams.TeamService;
import com.sportystreet.users.UserService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Read-only, explicitly simulated social content for the hackathon screens. */
@Service
@Profile("local")
public class CommunityService {
  public record ProfileData(
      UUID id,
      String username,
      String displayName,
      String teamName,
      String avatarUrl,
      String countryCode,
      boolean creator,
      int fans) {}

  public record MemberData(UUID profileId, int score, int wins) {}

  public record LeagueData(
      UUID id,
      String slug,
      String name,
      String type,
      String format,
      String inviteCode,
      String imageUrl,
      UUID creatorId,
      int seasonRounds,
      int roundsRemaining,
      String description,
      List<MemberData> members) {}

  public record RivalryData(
      UUID id,
      UUID leagueId,
      UUID leftProfileId,
      UUID rightProfileId,
      int leftWins,
      int rightWins,
      int draws,
      List<String> recentForm) {}

  public record ChallengeData(
      UUID id,
      UUID leagueId,
      UUID fromProfileId,
      UUID toProfileId,
      String roundLabel,
      String status) {}

  public record Preset(String id, String text) {}

  public record MessageData(
      UUID id,
      UUID leagueId,
      UUID authorId,
      String presetId,
      int minutesAgo,
      Map<String, Integer> reactions) {}

  public record ManagerData(
      UUID profileId,
      List<UUID> friendOf,
      double averagePoints,
      int topFinishes,
      int recentRounds,
      boolean topForm,
      String target,
      double multiplier,
      List<String> recentForm) {}

  public record Data(
      List<ProfileData> profiles,
      List<LeagueData> leagues,
      List<RivalryData> rivalries,
      List<ChallengeData> challenges,
      List<Preset> presets,
      List<MessageData> messages,
      List<ManagerData> managers) {}

  public record Person(
      UUID id,
      String displayName,
      String teamName,
      String avatarUrl,
      boolean creator,
      int fans,
      boolean isYou) {}

  public record LeagueMember(Person profile, int rank, int score, int wins) {}

  public record LeagueCard(
      UUID id,
      String slug,
      String name,
      String type,
      String format,
      String inviteCode,
      String imageUrl,
      String avatarUrl,
      Person creator,
      String description,
      int memberCount,
      boolean joined,
      Integer myRank,
      int seasonRounds,
      int roundsRemaining,
      List<Person> memberPreview) {}

  public record CommunityPage<T>(String dataMode, List<T> items, int page, int size, long total) {}

  public record LeagueDetail(
      String dataMode, boolean readOnly, LeagueCard league, List<LeagueMember> members) {}

  public record Rivalry(
      UUID id,
      Person you,
      Person opponent,
      int wins,
      int losses,
      int draws,
      List<String> recentForm) {}

  public record Challenge(
      UUID id, Person from, Person to, String direction, String roundLabel, String status) {}

  public record Rivalries(
      String dataMode, boolean readOnly, List<Rivalry> items, List<Challenge> challenges) {}

  public record BanterMessage(
      UUID id,
      Person author,
      String presetId,
      String text,
      Instant createdAt,
      Map<String, Integer> reactions) {}

  public record Banter(
      String dataMode,
      boolean readOnly,
      CommunityPage<BanterMessage> messages,
      List<Preset> quickMessages) {}

  public record ManagerCard(
      Person profile,
      boolean friend,
      boolean topForm,
      double averagePoints,
      int topFinishes,
      int recentRounds,
      String target,
      double multiplier,
      List<String> recentForm,
      List<CatalogueService.PlayerResponse> squadPreview,
      UUID captainPlayerId) {}

  public record Managers(
      String dataMode,
      boolean readOnly,
      UUID roundId,
      String roundName,
      String roundStatus,
      List<ManagerCard> items,
      int page,
      int size,
      long total) {}

  private static final String MODE = "SIMULATED";
  private final Data data;
  private final Map<UUID, ProfileData> profiles;
  private final UserService users;
  private final RoundService rounds;
  private final TeamService teams;
  private final Instant contentTime;

  public CommunityService(
      ObjectMapper json, UserService users, RoundService rounds, TeamService teams, Clock clock)
      throws IOException {
    try (var input = new ClassPathResource("seed/community.json").getInputStream()) {
      data = json.readValue(input, Data.class);
    }
    profiles = new LinkedHashMap<>();
    data.profiles().forEach(p -> profiles.put(p.id(), p));
    this.users = users;
    this.rounds = rounds;
    this.teams = teams;
    contentTime = clock.instant();
  }

  private Person person(UUID id, UUID viewer) {
    var p = profiles.get(id);
    if (p == null) throw ApiException.notFound();
    String name = p.displayName(), avatar = p.avatarUrl();
    if (p.username() != null) {
      var user = users.byUsername(p.username());
      name = user.displayName();
      avatar = user.avatarUrl();
    }
    return new Person(p.id(), name, p.teamName(), avatar, p.creator(), p.fans(), id.equals(viewer));
  }

  private LeagueData league(UUID id) {
    return data.leagues().stream()
        .filter(l -> l.id().equals(id))
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  private List<LeagueMember> members(LeagueData league, UUID viewer) {
    var ordered =
        league.members().stream()
            .sorted(
                Comparator.comparingInt(MemberData::score)
                    .reversed()
                    .thenComparing(MemberData::profileId))
            .toList();
    var result = new ArrayList<LeagueMember>();
    int rank = 0;
    for (int i = 0; i < ordered.size(); i++) {
      var m = ordered.get(i);
      if (i == 0 || ordered.get(i - 1).score() != m.score()) rank = i + 1;
      result.add(new LeagueMember(person(m.profileId(), viewer), rank, m.score(), m.wins()));
    }
    return result;
  }

  private LeagueCard card(LeagueData l, UUID viewer) {
    var members = members(l, viewer);
    Integer rank =
        members.stream()
            .filter(m -> m.profile().isYou())
            .map(LeagueMember::rank)
            .findFirst()
            .orElse(null);
    var creator = l.creatorId() == null ? null : person(l.creatorId(), viewer);
    return new LeagueCard(
        l.id(),
        l.slug(),
        l.name(),
        l.type(),
        l.format(),
        l.inviteCode(),
        l.imageUrl(),
        creator == null ? l.imageUrl() : creator.avatarUrl(),
        creator,
        l.description(),
        members.size(),
        rank != null,
        rank,
        l.seasonRounds(),
        l.roundsRemaining(),
        members.stream().limit(4).map(LeagueMember::profile).toList());
  }

  private static <T> CommunityPage<T> page(List<T> items, int page, int size) {
    var result = PageResponse.of(items, page, size);
    return new CommunityPage<>(MODE, result.items(), page, size, result.total());
  }

  public CommunityPage<LeagueCard> leagues(UUID viewer, String scope, int page, int size) {
    if (!Set.of("MINE", "DISCOVER", "ALL").contains(scope))
      throw new ApiException(400, "VALIDATION_FAILED", "scope must be MINE, DISCOVER or ALL");
    return page(
        data.leagues().stream()
            .map(l -> card(l, viewer))
            .filter(
                l ->
                    scope.equals("ALL")
                        || (scope.equals("MINE")
                            ? l.joined()
                            : l.type().equals("CREATOR") && !l.joined()))
            .toList(),
        page,
        size);
  }

  public LeagueDetail detail(UUID id, UUID viewer) {
    var league = league(id);
    return new LeagueDetail(MODE, true, card(league, viewer), members(league, viewer));
  }

  public CommunityPage<LeagueMember> table(UUID id, UUID viewer, int page, int size) {
    return page(members(league(id), viewer), page, size);
  }

  public Rivalries rivalries(UUID id, UUID viewer) {
    league(id);
    var items =
        data.rivalries().stream()
            .filter(r -> r.leagueId().equals(id))
            .filter(r -> r.leftProfileId().equals(viewer) || r.rightProfileId().equals(viewer))
            .map(
                r -> {
                  boolean left = r.leftProfileId().equals(viewer);
                  return new Rivalry(
                      r.id(),
                      person(viewer, viewer),
                      person(left ? r.rightProfileId() : r.leftProfileId(), viewer),
                      left ? r.leftWins() : r.rightWins(),
                      left ? r.rightWins() : r.leftWins(),
                      r.draws(),
                      left
                          ? r.recentForm()
                          : r.recentForm().stream()
                              .map(v -> v.equals("W") ? "L" : v.equals("L") ? "W" : "D")
                              .toList());
                })
            .toList();
    var challenges =
        data.challenges().stream()
            .filter(c -> c.leagueId().equals(id))
            .filter(c -> c.fromProfileId().equals(viewer) || c.toProfileId().equals(viewer))
            .map(
                c ->
                    new Challenge(
                        c.id(),
                        person(c.fromProfileId(), viewer),
                        person(c.toProfileId(), viewer),
                        c.toProfileId().equals(viewer) ? "INCOMING" : "OUTGOING",
                        c.roundLabel(),
                        c.status()))
            .toList();
    return new Rivalries(MODE, true, items, challenges);
  }

  public Banter banter(UUID id, UUID viewer, int page, int size) {
    league(id);
    var texts = new HashMap<String, String>();
    data.presets().forEach(p -> texts.put(p.id(), p.text()));
    var messages =
        data.messages().stream()
            .filter(m -> m.leagueId().equals(id))
            .sorted(Comparator.comparingInt(MessageData::minutesAgo))
            .map(
                m ->
                    new BanterMessage(
                        m.id(),
                        person(m.authorId(), viewer),
                        m.presetId(),
                        texts.get(m.presetId()),
                        contentTime.minusSeconds(m.minutesAgo() * 60L),
                        m.reactions()))
            .toList();
    return new Banter(MODE, true, page(messages, page, size), data.presets());
  }

  private boolean friend(ManagerData manager, UUID viewer) {
    return manager.friendOf().contains(viewer)
        || data.managers().stream()
            .anyMatch(
                m -> m.profileId().equals(viewer) && m.friendOf().contains(manager.profileId()));
  }

  public Managers managers(UUID roundId, UUID viewer, String filter, int page, int size) {
    if (!Set.of("ALL", "FRIENDS", "TOP_FORM", "CREATORS").contains(filter))
      throw new ApiException(
          400, "VALIDATION_FAILED", "filter must be ALL, FRIENDS, TOP_FORM or CREATORS");
    var round = rounds.get(roundId);
    var selected =
        data.managers().stream()
            .filter(m -> !m.profileId().equals(viewer))
            .filter(
                m ->
                    switch (filter) {
                      case "FRIENDS" -> friend(m, viewer);
                      case "TOP_FORM" -> m.topForm();
                      case "CREATORS" -> profiles.get(m.profileId()).creator();
                      default -> true;
                    })
            .sorted(
                Comparator.comparingDouble(ManagerData::averagePoints)
                    .reversed()
                    .thenComparing(ManagerData::profileId))
            .toList();
    var slice = PageResponse.of(selected, page, size);
    var cards =
        slice.items().stream()
            .map(
                m -> {
                  var p = profiles.get(m.profileId());
                  var person = person(p.id(), viewer);
                  var countryId =
                      UUID.nameUUIDFromBytes(
                          ("country:" + p.countryCode()).getBytes(StandardCharsets.UTF_8));
                  var user =
                      p.username() == null
                          ? new UserService.UserResponse(
                              p.id(), p.id().toString(), p.displayName(), countryId, p.avatarUrl())
                          : users.byUsername(p.username());
                  // Preview selections are generated; never expose another user's saved lineup.
                  var pick = teams.autoPick(roundId, user, true);
                  return new ManagerCard(
                      person,
                      friend(m, viewer),
                      m.topForm(),
                      m.averagePoints(),
                      m.topFinishes(),
                      m.recentRounds(),
                      m.target(),
                      m.multiplier(),
                      m.recentForm(),
                      pick.starters(),
                      pick.captainPlayerId());
                })
            .toList();
    return new Managers(
        MODE, true, roundId, round.name(), round.status(), cards, page, size, slice.total());
  }
}
