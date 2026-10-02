package io.fleettruth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.domain.Telemetry.*;
import io.fleettruth.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
  properties = {
    "fleettruth.vehicles=64",
    "fleettruth.simulation=false",
    "spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "management.health.redis.enabled=false",
  }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  FleetService fleet;

  @Autowired
  Simulator simulator;

  @Autowired
  ObjectMapper json;

  @Autowired
  org.springframework.transaction.support.TransactionTemplate tx;

  private void assertRollupsMatchLedger() {
    for (String kind : List.of("TOTAL", "MINUTE", "DAY")) {
      for (String tenant : List.of("tenant-demo", "tenant-other")) {
        assertThat(fleet.count("SELECT COALESCE(SUM(total),0) FROM telemetry_rollups WHERE tenant_id=? AND kind=?", tenant, kind))
          .as("%s %s totals", tenant, kind)
          .isEqualTo(fleet.count("SELECT COUNT(*) FROM raw_events WHERE tenant_id=?", tenant));
        for (String status : List.of("ACCEPTED", "QUARANTINED")) {
          assertThat(fleet.count("SELECT COALESCE(SUM(" + status.toLowerCase(Locale.ROOT) + "),0) FROM telemetry_rollups WHERE tenant_id=? AND kind=?", tenant, kind))
            .isEqualTo(fleet.count("SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND status=?", tenant, status));
        }
      }
    }
  }

  @Test
  void rolledBackIngestDoesNotInflateSummaries() {
    var event = simulator.event(16, 345678, Instant.now(), "1", 50.0);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
      fleet.ingest("tenant-demo", event);
      throw new IllegalStateException("Deliberate test rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(fleet.count("SELECT COUNT(*) FROM raw_events WHERE id=?", event.eventId())).isZero();
    assertRollupsMatchLedger();
  }

  @Test
  void concurrentWritersDoNotLoseSummaryCounts() throws Exception {
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
      for (int i = 0; i < 12; i++) {
        int index = i;
        tasks.add(() -> fleet.ingest("tenant-demo", simulator.event(20 + (index % 4) * 4,
          400000 + index, Instant.now().minusSeconds(2 * 86400L).plusSeconds(index), "1", 50.0)).status());
      }
      for (var result : workers.invokeAll(tasks)) assertThat(result.get()).isEqualTo("ACCEPTED");
    }
    assertRollupsMatchLedger();
  }

  private JwtRequestPostProcessor engineer() {
    return jwt()
      .jwt(j ->
        j
          .subject("engineer@test")
          .claim("tenant_id", "tenant-demo")
          .claim("roles", List.of("ENGINEER"))
      )
      .authorities(new SimpleGrantedAuthority("ROLE_ENGINEER"));
  }

  private JwtRequestPostProcessor viewer() {
    return jwt()
      .jwt(j ->
        j
          .subject("viewer@test")
          .claim("tenant_id", "tenant-demo")
          .claim("roles", List.of("VIEWER"))
      )
      .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
  }

  @Test
  void anonymousAccessAndInvalidLoginAreRejected() throws Exception {
    mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"email\":\"engineer@fleettruth.demo\",\"password\":\"incorrect\"}"
          )
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"email\":\"engineer@fleettruth.demo\",\"password\":\"FleetTruth2026!\"}"
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.token").isString());
  }

  @Test
  void tenantIsolationIsAppliedToDetailAndLists() throws Exception {
    var other = jwt()
      .jwt(j ->
        j
          .subject("other")
          .claim("tenant_id", "tenant-other")
          .claim("roles", List.of("VIEWER"))
      )
      .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    mvc
      .perform(get("/api/vehicles/" + Simulator.vin(1)).with(other))
      .andExpect(status().isNotFound());
    mvc
      .perform(get("/api/vehicles").with(other))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").value(1));
    mvc
      .perform(get("/api/overview").with(other))
      .andExpect(jsonPath("$.processed").value(0));
  }

  @Test
  void overviewAggregatesMatchTheLedgerAndRemainTenantScoped() {
    assertRollupsMatchLedger();
    var overview = fleet.overview("tenant-demo");
    assertThat(((Number) overview.get("processed")).longValue()).isEqualTo(
      fleet.count(
        "SELECT COUNT(*) FROM raw_events WHERE tenant_id=?",
        "tenant-demo"
      )
    );
    assertThat(((Number) overview.get("vehicles")).longValue()).isEqualTo(
      fleet.count(
        "SELECT COUNT(*) FROM vehicles WHERE tenant_id=?",
        "tenant-demo"
      )
    );
    for (String status : List.of("ACCEPTED", "QUARANTINED")) {
      assertThat(
        ((Number) overview.get(status.toLowerCase(Locale.ROOT))).longValue()
      ).isEqualTo(
        fleet.count(
          "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND status=?",
          "tenant-demo",
          status
        )
      );
    }
    var series = fleet.series("tenant-demo");
    assertThat(series).hasSize(61);
    Instant start = Instant.parse((String) series.getFirst().get("time"));
    for (String status : List.of("ACCEPTED", "QUARANTINED")) {
      long actual = series
        .stream()
        .mapToLong(row ->
          ((Number) row.get(status.toLowerCase(Locale.ROOT))).longValue()
        )
        .sum();
      assertThat(actual).isEqualTo(
        fleet.count(
          "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND status=? AND received_at>=? AND received_at<?",
          "tenant-demo",
          status,
          FleetService.time(start),
          FleetService.time(start.plusSeconds(61 * 60L))
        )
      );
    }
    assertThat(fleet.series("tenant-other")).allSatisfy(point -> {
      assertThat(((Number) point.get("accepted")).longValue()).isZero();
      assertThat(((Number) point.get("quarantined")).longValue()).isZero();
    });
  }

  @Test
  void viewerCannotMutateOrReadRawEvidence() throws Exception {
    mvc
      .perform(post("/api/simulation/drift").with(viewer()))
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/replays")
          .with(viewer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"oem\":\"helix\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/events/private-id").with(viewer()))
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/vehicles/" + Simulator.vin(1)).with(viewer()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.events").doesNotExist());
  }

  @Test
  @Order(1)
  void workflowRecoversUnknownSchemaAndPreservesAudit() throws Exception {
    var draft = fleet
      .mappings("tenant-demo")
      .stream()
      .filter(m -> m.get("status").equals("DRAFT"))
      .findFirst()
      .orElseThrow();
    String mappingId = (String) draft.get("id");
    mvc
      .perform(
        post("/api/mappings/" + mappingId + "/preview")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.invalid").value(0));
    mvc
      .perform(
        post("/api/mappings/" + mappingId + "/approve")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/mappings/" + mappingId + "/approve")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isConflict());
    String response = mvc
      .perform(
        post("/api/replays")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"oem\":\"helix\"}")
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    String id = json.readTree(response).get("id").asText();
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(10))
      .until(
        () ->
          fleet.count(
            "SELECT COUNT(*) FROM replay_jobs WHERE id=? AND status='COMPLETED'",
            id
          ) == 1
      );
    assertThat(
      fleet.count(
        "SELECT COUNT(*) FROM raw_events WHERE tenant_id='tenant-demo' AND oem_id='helix' AND status='QUARANTINED'"
      )
    ).isZero();
    assertThat(
      fleet.count(
        "SELECT COUNT(*) FROM audit_log WHERE action='MAPPING_APPROVED'"
      )
    ).isPositive();
    assertThat(
      fleet.count("SELECT SUM(recovered) FROM replay_jobs WHERE id=?", id)
    ).isPositive();
    assertRollupsMatchLedger();
  }

  @Test
  void duplicateAndOutOfOrderEventsCannotCorruptLatestState() {
    String tenant = "tenant-demo";
    Event recent = simulator.event(0, 9999, Instant.now(), "1", 44.0);
    assertThat(fleet.ingest(tenant, recent).status()).isEqualTo("ACCEPTED");
    assertThat(fleet.ingest(tenant, recent).status()).isEqualTo("DUPLICATE");
    Event old = simulator.event(
      0,
      9000,
      Instant.now().minusSeconds(100),
      "1",
      22.0
    );
    fleet.ingest(tenant, old);
    assertThat(
      ((Number) fleet.vehicle(tenant, recent.vin()).get("socPct")).doubleValue()
    ).isEqualTo(44);
    assertRollupsMatchLedger();
  }

  @Test
  @Order(2)
  void invalidMappingFailsValidationWithoutApproval() throws Exception {
    var draft = simulator.injectDrift("tenant-demo", "test");
    String id = (String) draft.get("mappingId");
    var rules = new ArrayList<>(fleet.mapping("tenant-demo", id).rules());
    rules.set(1, new Rule("socPct", "/missing", 100, 0, true));
    String body = json.writeValueAsString(Map.of("rules", rules));
    mvc
      .perform(
        post("/api/mappings/" + id + "/approve")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void paginationAndSearchAreStable() throws Exception {
    mvc
      .perform(get("/api/vehicles?limit=5").with(engineer()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(5))
      .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    mvc
      .perform(get("/api/vehicles?search=missing").with(engineer()))
      .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void assistantCannotApproveAndUsesAuditedTools() throws Exception {
    mvc
      .perform(
        post("/api/assistant")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"Approve every mapping\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tools.length()").value(0));
    mvc
      .perform(
        post("/api/assistant")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"What needs attention?\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tools.length()").value(2));
  }

  @Test
  void mlAndKnowledgeEndpointsReturnEvidence() throws Exception {
    mvc
      .perform(get("/api/ml/drift").with(engineer()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.advisory").value(true))
      .andExpect(jsonPath("$.streams.length()").value(4));
    mvc
      .perform(
        get("/api/knowledge?query=Helix%20soc_fraction").with(engineer())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.results.length()").value(2));
  }

  @Test
  @Order(3)
  void assistantDriftAndRecoveryUseReadOnlyTools() throws Exception {
    mvc
      .perform(
        post("/api/assistant")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"Explain the Helix schema drift\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tools.length()").value(4));
    mvc
      .perform(
        post("/api/assistant")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"Which events need recovery?\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tools.length()").value(1));
    mvc
      .perform(
        post("/api/assistant")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"\"}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void readContractsIngestAndAlertTransitionsWork() throws Exception {
    for (String path : List.of(
      "/api/oems",
      "/api/mappings",
      "/api/audit?includeReads=true",
      "/api/analytics",
      "/api/simulation",
      "/api/auth/me",
      "/api/replays"
    ))
      mvc.perform(get(path).with(engineer())).andExpect(status().isOk());
    var event = simulator.event(12, 900000, Instant.now(), "1", 6.0);
    mvc
      .perform(
        post("/api/ingest")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content(json.writeValueAsString(event))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.eventId").value(event.eventId()));
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(30))
      .until(
        () ->
          fleet.count(
            "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND id=?",
            "tenant-demo",
            event.eventId()
          ) == 1
      );
    mvc
      .perform(get("/api/events/" + event.eventId()).with(engineer()))
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/export?limit=2").with(engineer()))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"));
    String alertId = (String) fleet
      .alerts("tenant-demo", "ALL", 1)
      .getFirst()
      .get("id");
    mvc
      .perform(
        patch("/api/alerts/" + alertId)
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"status\":\"ACKNOWLEDGED\"}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        patch("/api/alerts/" + alertId)
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"status\":\"INVALID\"}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void equalTimestampUsesSequenceAndOlderAlertsCannotReplaceNewEvidence() {
    var time = Instant.now();
    var first = simulator.event(8, 100000, time, "1", 7.0);
    var old = simulator.event(8, 99999, time, "1", 14.0);
    fleet.ingest("tenant-demo", first);
    fleet.ingest("tenant-demo", old);
    assertThat(
      (
        (Number) fleet.vehicle("tenant-demo", first.vin()).get("socPct")
      ).doubleValue()
    ).isEqualTo(7);
    assertThat(
      fleet
        .rows(
          "SELECT severity,event_id FROM alerts WHERE tenant_id=? AND vin=? AND kind='LOW_BATTERY' AND status<>'RESOLVED'",
          "tenant-demo",
          first.vin()
        )
        .getFirst()
    )
      .containsEntry("severity", "CRITICAL")
      .containsEntry("eventId", first.eventId());
  }

  @Test
  void missingCanonicalRulesCannotBeApproved() {
    var m = fleet
      .mappings("tenant-demo")
      .stream()
      .filter(row -> row.get("status").equals("DRAFT"))
      .findFirst()
      .orElseThrow();
    assertThat(
      (Integer) fleet
        .preview("tenant-demo", (String) m.get("id"), List.of())
        .get("invalid")
    ).isPositive();
  }

  @Test
  @Order(99)
  void adminErasureRemovesLocalEvidenceAndRejectsResurrection()
    throws Exception {
    String vin = Simulator.vin(61);
    fleet.ingest("tenant-demo", simulator.event(60, 123455, Instant.now(), "1", 42.0));
    var admin = jwt()
      .jwt(j ->
        j
          .subject("admin@test")
          .claim("tenant_id", "tenant-demo")
          .claim("roles", List.of("ADMIN"))
      )
      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String body = json.writeValueAsString(
      Map.of("vin", vin, "confirmation", vin)
    );
    mvc
      .perform(
        post("/api/privacy/erase")
          .with(engineer())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/privacy/erase")
          .with(admin)
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("LOCAL_PURGED_EXTERNAL_PENDING"));
    mvc
      .perform(get("/api/vehicles/" + vin).with(engineer()))
      .andExpect(status().isNotFound());
    assertThat(
      fleet.count(
        "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND vin=?",
        "tenant-demo",
        vin
      )
    ).isZero();
    assertThat(
      fleet.count(
        "SELECT COUNT(*) FROM projection_outbox WHERE tenant_id=? AND vin=?",
        "tenant-demo",
        vin
      )
    ).isZero();
    assertThatThrownBy(() ->
      fleet.ingest(
        "tenant-demo",
        simulator.event(60, 123456, Instant.now(), "1", 42.0)
      )
    ).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertRollupsMatchLedger();
  }
}
