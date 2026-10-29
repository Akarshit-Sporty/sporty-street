package com.sportystreet.users;

import com.sportystreet.shared.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
  public record UserResponse(
      UUID id, String username, String displayName, UUID countryId, String avatarUrl) {}

  private final UserRepository users;

  public UserService(UserRepository users) {
    this.users = users;
  }

  public UserResponse byUsername(String username) {
    var user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Unknown demo user"));
    return new UserResponse(
        user.id, user.username, user.displayName, user.countryId, user.avatarUrl);
  }

  public UserResponse byId(UUID id) {
    var user = users.findById(id).orElseThrow(ApiException::notFound);
    return new UserResponse(
        user.id, user.username, user.displayName, user.countryId, user.avatarUrl);
  }
}
