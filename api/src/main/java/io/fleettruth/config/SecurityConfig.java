package io.fleettruth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private final byte[] secret;

  public SecurityConfig(
    @Value("${fleettruth.jwt-secret:}") String configured,
    @Value("${fleettruth.oidc-issuer:}") String issuer,
    Environment env
  ) {
    boolean local =
      Arrays.asList(env.getActiveProfiles()).contains("local") ||
      env.getActiveProfiles().length == 0 ||
      Arrays.asList(env.getActiveProfiles()).contains("test");
    if (!local && issuer.isBlank()) throw new IllegalStateException(
      "OIDC_ISSUER is required outside the local/test profiles"
    );
    if (configured.isBlank()) {
      secret = new byte[32];
      new SecureRandom().nextBytes(secret);
    } else {
      secret = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (secret.length < 32) throw new IllegalArgumentException(
        "JWT_SECRET must contain at least 32 bytes"
      );
    }
  }

  @Bean
  JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secret));
  }

  @Bean
  JwtDecoder jwtDecoder(
    @Value("${fleettruth.oidc-issuer:}") String issuer,
    @Value("${fleettruth.oidc-audience:fleettruth-api}") String audience
  ) {
    if (!issuer.isBlank()) {
      var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
      decoder.setJwtValidator(
        new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
          JwtValidators.createDefaultWithIssuer(issuer),
          new JwtClaimValidator<List<String>>(
            "aud",
            a -> a != null && a.contains(audience)
          )
        )
      );
      return decoder;
    }
    var decoder = NimbusJwtDecoder.withSecretKey(
      new SecretKeySpec(secret, "HmacSHA256")
    )
      .macAlgorithm(MacAlgorithm.HS256)
      .build();
    decoder.setJwtValidator(
      JwtValidators.createDefaultWithIssuer("fleettruth-local")
    );
    return decoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles == null) roles = List.of("VIEWER");
      return roles
        .stream()
        .filter(r -> Set.of("ADMIN", "ENGINEER", "VIEWER").contains(r))
        .<org.springframework.security.core.GrantedAuthority>map(r ->
          new SimpleGrantedAuthority("ROLE_" + r)
        )
        .toList();
    });
    return http
      .csrf(c -> c.disable())
      .sessionManagement(s ->
        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .headers(h ->
        h.contentSecurityPolicy(c ->
          c.policyDirectives("default-src 'none'; frame-ancestors 'none'")
        )
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/api/auth/login",
            "/api/auth/config",
            "/actuator/health",
            "/actuator/health/**"
          )
          .permitAll()
          .requestMatchers("/actuator/**")
          .hasRole("ADMIN")
          .requestMatchers(HttpMethod.OPTIONS, "/**")
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .oauth2ResourceServer(o ->
        o.jwt(j -> j.jwtAuthenticationConverter(converter))
      )
      .build();
  }

  @Bean
  OncePerRequestFilter requestBounds() {
    return new OncePerRequestFilter() {
      record Window(long bucket, int count) {}

      final Map<String, Window> limits = new ConcurrentHashMap<>();

      @Override
      protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
      ) throws ServletException, IOException {
        if (request.getContentLengthLong() > 2_000_000) {
          response.sendError(413);
          return;
        }
        String key =
          request.getRemoteAddr() +
          (request.getRequestURI().equals("/api/auth/login")
            ? ":login"
            : ":api");
        long bucket = System.currentTimeMillis() / 60000;
        Window win = limits.compute(key, (k, old) ->
          old == null || old.bucket() != bucket
            ? new Window(bucket, 1)
            : new Window(bucket, old.count() + 1)
        );
        int ceiling = key.endsWith(":login") ? 30 : 6000;
        if (win.count() > ceiling) {
          response.setHeader("Retry-After", "60");
          response.sendError(429);
          return;
        }
        if (limits.size() > 10000) limits
          .entrySet()
          .removeIf(e -> e.getValue().bucket() < bucket);
        chain.doFilter(request, response);
      }
    };
  }
}
