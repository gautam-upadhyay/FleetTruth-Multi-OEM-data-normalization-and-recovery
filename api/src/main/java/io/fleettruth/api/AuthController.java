package io.fleettruth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final JwtEncoder encoder;
  private final boolean local;
  private final String issuer;
  private final String clientId;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final String demoHash = passwords.encode("FleetTruth2026!");

  public AuthController(
    JwtEncoder encoder,
    Environment env,
    @Value("${fleettruth.oidc-issuer:}") String issuer,
    @Value("${fleettruth.oidc-client-id:fleettruth-web}") String clientId
  ) {
    this.encoder = encoder;
    this.issuer = issuer;
    this.clientId = clientId;
    local =
      issuer.isBlank() &&
      (env.getActiveProfiles().length == 0 ||
        Arrays.stream(env.getActiveProfiles()).anyMatch(
          p -> p.equals("local") || p.equals("test")
        ));
  }

  public record Login(
    @Email @NotBlank String email,
    @NotBlank @Size(max = 100) String password
  ) {}

  @GetMapping("/config")
  public Map<String, Object> config() {
    return Map.of(
      "local",
      local,
      "issuer",
      issuer,
      "clientId",
      clientId,
      "product",
      "FleetTruth"
    );
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody Login input) {
    String email = input.email().toLowerCase(Locale.ROOT);
    boolean known = Set.of(
      "engineer@fleettruth.demo",
      "viewer@fleettruth.demo",
      "admin@fleettruth.demo",
      "other@fleettruth.demo"
    ).contains(email);
    if (
      !local || !known || !passwords.matches(input.password(), demoHash)
    ) throw new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "Invalid credentials"
    );
    String role = email.startsWith("viewer")
        ? "VIEWER"
        : email.startsWith("admin")
          ? "ADMIN"
          : "ENGINEER",
      tenant = email.startsWith("other") ? "tenant-other" : "tenant-demo";
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder()
      .issuer("fleettruth-local")
      .subject(email)
      .issuedAt(now)
      .expiresAt(now.plusSeconds(14400))
      .claim("tenant_id", tenant)
      .claim("roles", List.of(role))
      .build();
    String token = encoder
      .encode(
        JwtEncoderParameters.from(
          JwsHeader.with(MacAlgorithm.HS256).build(),
          claims
        )
      )
      .getTokenValue();
    return Map.of(
      "token",
      token,
      "user",
      Map.of(
        "email",
        email,
        "name",
        role.equals("VIEWER") ? "Fleet reviewer" : "Alex Morgan",
        "role",
        role,
        "tenantId",
        tenant
      )
    );
  }

  @GetMapping("me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return Map.of(
      "email",
      jwt.getSubject(),
      "roles",
      roles == null ? List.of("VIEWER") : roles,
      "tenantId",
      FleetController.tenant(jwt)
    );
  }
}
