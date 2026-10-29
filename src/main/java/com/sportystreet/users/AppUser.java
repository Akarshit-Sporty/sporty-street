package com.sportystreet.users;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "app_user")
class AppUser {
  @Id UUID id;
  String username;

  @Column(name = "display_name")
  String displayName;

  @Column(name = "country_id")
  UUID countryId;

  @Column(name = "avatar_url")
  String avatarUrl;

  protected AppUser() {}
}
