package io.fleettruth.api;

import io.fleettruth.domain.Telemetry.*;
import io.fleettruth.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class FleetController {

  private final FleetService fleet;
  private final Simulator simulator;
  private final AssistantService assistant;
  private final EventIntake intake;
  private final DriftModel model;
  private final PrivacyService privacy;
  private final KnowledgeService knowledge;

  public FleetController(
    FleetService fleet,
    Simulator simulator,
    AssistantService assistant,
    EventIntake intake,
    DriftModel model,
    PrivacyService privacy,
    KnowledgeService knowledge
  ) {
    this.fleet = fleet;
    this.simulator = simulator;
    this.assistant = assistant;
    this.intake = intake;
    this.model = model;
    this.privacy = privacy;
    this.knowledge = knowledge;
  }

  static String tenant(Jwt jwt) {
    String tenant = jwt.getClaimAsString("tenant_id");
    if (tenant == null || tenant.isBlank()) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "A tenant claim is required"
    );
    return tenant;
  }

  private String read(Jwt jwt, String resource) {
    String tenant = tenant(jwt);
    fleet.audit(
      tenant,
      jwt.getSubject(),
      "DATA_READ",
      resource,
      "Authorized tenant-scoped read"
    );
    return tenant;
  }

  @GetMapping("/overview")
  public Object overview(@AuthenticationPrincipal Jwt jwt) {
    return fleet.overview(read(jwt, "overview"));
  }

  @GetMapping("/vehicles")
  public Object vehicles(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "") String search,
    @RequestParam(defaultValue = "") String oem,
    @RequestParam(defaultValue = "") String cursor,
    @RequestParam(defaultValue = "25") int limit
  ) {
    var page = fleet.vehicles(
      read(jwt, "vehicles"),
      search,
      oem,
      cursor,
      limit
    );
    if (!precise(jwt)) {
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) page.get("items");
      items.forEach(this::mask);
    }
    return page;
  }

  @GetMapping("/vehicles/{vin}")
  public Object vehicle(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String vin
  ) {
    var value = fleet.vehicle(read(jwt, vin), vin);
    if (!precise(jwt)) {
      mask(value);
      value.remove("events");
    }
    return value;
  }

  private boolean precise(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return (
      roles != null &&
      roles.stream().anyMatch(r -> r.equals("ADMIN") || r.equals("ENGINEER"))
    );
  }

  private void mask(Map<String, Object> value) {
    for (String key : List.of("latitude", "longitude")) {
      if (value.get(key) instanceof Number n) value.put(
        key,
        Math.round(n.doubleValue() * 10) / 10.0
      );
    }
  }

  @GetMapping("/alerts")
  public Object alerts(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "ALL") String status
  ) {
    return fleet.alerts(read(jwt, "alerts"), status, 100);
  }

  @PatchMapping("/alerts/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object alert(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @RequestBody Map<String, String> input
  ) {
    fleet.updateAlert(
      tenant(jwt),
      jwt.getSubject(),
      id,
      input.getOrDefault("status", "")
    );
    return Map.of("status", "UPDATED");
  }

  @GetMapping("/oems")
  public Object oems(@AuthenticationPrincipal Jwt jwt) {
    return fleet.oems(read(jwt, "oems"));
  }

  @GetMapping("/mappings")
  public Object mappings(@AuthenticationPrincipal Jwt jwt) {
    return fleet.mappings(read(jwt, "mappings"));
  }

  public record MappingRequest(List<Rule> rules) {}

  @PostMapping("/mappings/{id}/preview")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object preview(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @RequestBody(required = false) MappingRequest input
  ) {
    fleet.audit(
      tenant(jwt),
      jwt.getSubject(),
      "MAPPING_DRY_RUN",
      id,
      "Sample validation requested"
    );
    return fleet.preview(tenant(jwt), id, input == null ? null : input.rules());
  }

  @PostMapping("/mappings/{id}/approve")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object approve(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @RequestBody(required = false) MappingRequest input
  ) {
    fleet.approve(
      tenant(jwt),
      jwt.getSubject(),
      id,
      input == null ? null : input.rules()
    );
    return Map.of("status", "APPROVED");
  }

  @GetMapping("/events/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object event(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id
  ) {
    return fleet.eventDetail(read(jwt, id), id);
  }

  @PostMapping("/ingest")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object ingest(
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody Event event
  ) {
    return intake.accept(tenant(jwt), event);
  }

  @GetMapping("/replays")
  public Object replays(@AuthenticationPrincipal Jwt jwt) {
    return fleet.rows(
      "SELECT * FROM replay_jobs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
      read(jwt, "replays")
    );
  }

  @PostMapping("/replays")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object replay(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody Map<String, String> input
  ) {
    return fleet.startReplay(
      tenant(jwt),
      jwt.getSubject(),
      input.getOrDefault("oem", "helix")
    );
  }

  @GetMapping("/audit")
  public Object audit(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "false") boolean includeReads
  ) {
    String tenant = read(jwt, "audit");
    return fleet.rows(
      "SELECT * FROM audit_log WHERE tenant_id=? AND (?=TRUE OR action<>'DATA_READ') ORDER BY created_at DESC LIMIT 150",
      tenant,
      includeReads
    );
  }

  @GetMapping("/analytics")
  public Object analytics(@AuthenticationPrincipal Jwt jwt) {
    return fleet.analytics(read(jwt, "analytics"));
  }

  @GetMapping("/ml/drift")
  public Object driftScores(@AuthenticationPrincipal Jwt jwt) {
    return model.inspect(fleet, read(jwt, "drift-model"));
  }

  @GetMapping("/knowledge")
  public Object knowledge(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam String query
  ) {
    read(jwt, "runbooks");
    return Map.of(
      "storage",
      knowledge.storage(),
      "results",
      knowledge.search(query)
    );
  }

  @GetMapping("/privacy/erasures")
  @PreAuthorize("hasRole('ADMIN')")
  public Object erasures(@AuthenticationPrincipal Jwt jwt) {
    return fleet.rows(
      "SELECT * FROM erasures WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
      read(jwt, "erasures")
    );
  }

  @PostMapping("/privacy/erase")
  @PreAuthorize("hasRole('ADMIN')")
  public Object erase(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody Map<String, String> input
  ) {
    return privacy.erase(
      tenant(jwt),
      jwt.getSubject(),
      input.getOrDefault("vin", ""),
      input.getOrDefault("confirmation", "")
    );
  }

  @GetMapping("/simulation")
  public Object simulation(@AuthenticationPrincipal Jwt jwt) {
    read(jwt, "simulation");
    return simulator.status();
  }

  @PostMapping("/simulation")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object simulation(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody Map<String, Boolean> input
  ) {
    if (
      !tenant(jwt).equals(Simulator.TENANT)
    ) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    fleet.audit(
      tenant(jwt),
      jwt.getSubject(),
      "SIMULATION_CHANGED",
      "simulator",
      "Running: " + input.getOrDefault("running", true)
    );
    return simulator.toggle(input.getOrDefault("running", true));
  }

  @PostMapping("/simulation/drift")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public Object drift(@AuthenticationPrincipal Jwt jwt) {
    return simulator.injectDrift(tenant(jwt), jwt.getSubject());
  }

  @PostMapping("/assistant")
  public Object assistant(
    @AuthenticationPrincipal Jwt jwt,
    @RequestBody Map<String, String> input
  ) {
    return assistant.answer(
      tenant(jwt),
      jwt.getSubject(),
      input.getOrDefault("question", "")
    );
  }

  @GetMapping(value = "/export", produces = "application/x-ndjson")
  @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
  public String export(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "") String cursor,
    @RequestParam(defaultValue = "5000") int limit,
    @RequestParam(required = false) java.time.Instant before
  ) {
    String tenant = read(jwt, "export");
    if (before == null) before = java.time.Instant.now();
    var rows = fleet.rows(
      "SELECT id,vin,oem_id,event_time,status,mapping_id,normalized FROM raw_events WHERE tenant_id=? AND id>? AND received_at<=? ORDER BY id LIMIT ?",
      tenant,
      cursor,
      FleetService.time(before),
      Math.max(1, Math.min(limit, 10000))
    );
    return rows
      .stream()
      .map(fleet::encode)
      .collect(java.util.stream.Collectors.joining("\n"));
  }
}
