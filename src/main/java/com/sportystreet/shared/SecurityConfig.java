package com.sportystreet.shared;

import com.sportystreet.users.UserService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noPasswordLogin() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Password login is not supported");
    };
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      Environment environment,
      UserService users,
      ObjectMapper mapper,
      @Value("${sporty.provider.token}") String providerToken,
      @Value("${sporty.provider.name}") String providerName)
      throws Exception {
    boolean local = Arrays.asList(environment.getActiveProfiles()).contains("local");
    http.csrf(
            csrf -> csrf.disable()) // local-only demo identities; no real login/session in this MVP
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) -> error(response, mapper, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler(
                        (request, response, ex) -> error(response, mapper, 403, "FORBIDDEN")))
        .authorizeHttpRequests(
            auth -> {
              auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll();
              auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
              auth.requestMatchers(
                      org.springframework.http.HttpMethod.GET,
                      "/media/players/**",
                      "/media/avatars/**",
                      "/media/leagues/**",
                      "/media/rewards/**")
                  .permitAll();
              auth.requestMatchers(
                      org.springframework.http.HttpMethod.HEAD,
                      "/media/players/**",
                      "/media/avatars/**",
                      "/media/leagues/**",
                      "/media/rewards/**")
                  .permitAll();
              if (local) {
                auth.requestMatchers(
                        "/api/v1/dev/login",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/v1/internal/**")
                    .hasRole("PROVIDER")
                    .requestMatchers("/api/v1/**")
                    .hasRole("USER");
              }
              auth.anyRequest().denyAll();
            });
    if (local)
      http.addFilterBefore(
          new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
              try {
                if (request.getRequestURI().startsWith("/api/v1/internal/")) {
                  String supplied = request.getHeader("X-Provider-Token");
                  if (supplied != null
                      && !providerToken.isBlank()
                      && MessageDigest.isEqual(
                          supplied.getBytes(StandardCharsets.UTF_8),
                          providerToken.getBytes(StandardCharsets.UTF_8))) {
                    authenticate(providerName, "PROVIDER");
                  }
                } else if (request.getRequestURI().startsWith("/api/v1/")
                    && !request.getRequestURI().equals("/api/v1/dev/login")) {
                  String username = request.getHeader("X-Dev-User");
                  if (username == null && request.getCookies() != null) {
                    username =
                        Arrays.stream(request.getCookies())
                            .filter(c -> c.getName().equals("dev_user"))
                            .map(Cookie::getValue)
                            .findFirst()
                            .orElse(null);
                  }
                  if (username != null) authenticate(users.byUsername(username), "USER");
                }
              } catch (ApiException ex) {
                error(response, mapper, ex.status(), ex.code());
                return;
              }
              chain.doFilter(request, response);
            }
          },
          AnonymousAuthenticationFilter.class);
    return http.build();
  }

  private static void authenticate(Object principal, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
  }

  private static void error(
      HttpServletResponse response, ObjectMapper mapper, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(code, status == 401 ? "Authentication required" : "Access denied"));
  }
}
