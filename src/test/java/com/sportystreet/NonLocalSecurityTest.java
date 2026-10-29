package com.sportystreet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sportystreet.shared.SecurityConfig;
import com.sportystreet.users.UserController;
import com.sportystreet.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = UserController.class,
    properties = {"sporty.provider.token=", "sporty.provider.name=simulator"})
@Import(SecurityConfig.class)
class NonLocalSecurityTest {
  @MockitoBean UserService users;
  @Autowired MockMvc mvc;

  @Test
  void demoHeadersDoNotAuthenticateOutsideLocalProfile() throws Exception {
    mvc.perform(get("/api/v1/me").header("X-Dev-User", "alice"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/dev/login").param("username", "alice"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
  }
}
