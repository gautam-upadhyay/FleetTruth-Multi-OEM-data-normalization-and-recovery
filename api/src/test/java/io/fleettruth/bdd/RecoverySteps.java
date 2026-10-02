package io.fleettruth.bdd;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import com.fasterxml.jackson.databind.*;
import io.cucumber.java.en.*;
import io.cucumber.spring.CucumberContextConfiguration;
import io.fleettruth.domain.Telemetry.Event;
import io.fleettruth.service.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

@CucumberContextConfiguration
@SpringBootTest(properties={"fleettruth.vehicles=64","fleettruth.simulation=false","spring.datasource.url=jdbc:h2:mem:bdd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class RecoverySteps {
  @Autowired FleetService fleet;
  @Autowired Simulator simulator;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  private String mappingId, replayId, vin;
  private Event event;
  private int status;
  private JsonNode response;
  private JwtRequestPostProcessor role(String role) {
    return jwt().jwt(j -> j.subject("bdd@test").claim("tenant_id","tenant-demo").claim("roles",List.of(role)))
      .authorities(new SimpleGrantedAuthority("ROLE_"+role));
  }
  @Given("Helix schema {int} reports battery percentage")
  public void percentage(int schema) {
    fleet.ingest("tenant-demo",simulator.event(1,800000,Instant.now(),Integer.toString(schema),65.0));
    mappingId = (String) fleet.mappings("tenant-demo").stream().filter(m -> m.get("oemId").equals("helix") && m.get("status").equals("DRAFT")).findFirst().orElseThrow().get("id");
  }
  @When("schema {int} sends soc_fraction equal to {double}")
  public void fraction(int schema, double value) {
    event = simulator.event(1,800001,Instant.now().plusSeconds(1),Integer.toString(schema),value*100);
    assertThat(fleet.ingest("tenant-demo",event).status()).isEqualTo("QUARANTINED");
  }
  @Then("the event is quarantined without an approved schema {int} mapping")
  public void quarantine(int schema) { assertThat(fleet.eventDetail("tenant-demo",event.eventId()).get("status")).isEqualTo("QUARANTINED"); }
  @Then("its vehicle state is marked untrusted")
  public void untrusted() { assertThat(fleet.vehicle("tenant-demo",event.vin()).get("quality")).isEqualTo("UNTRUSTED"); }
  @When("an engineer validates the fraction-to-percentage mapping")
  public void validate() { var preview = fleet.preview("tenant-demo",mappingId,null); assertThat(preview.get("invalid")).isEqualTo(0); assertThat((Integer)preview.get("total")).isPositive(); }
  @When("explicitly approves the mapping")
  public void approve() throws Exception { assertThat(mvc.perform(post("/api/mappings/"+mappingId+"/approve").with(role("ENGINEER")).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(200); }
  @When("requests replay")
  public void replay() { replayId = (String)fleet.startReplay("tenant-demo","bdd@test","helix").get("id"); org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> fleet.count("SELECT COUNT(*) FROM replay_jobs WHERE id=? AND status='COMPLETED'",replayId)==1); }
  @Then("battery percentage becomes {int}")
  public void battery(int value) { assertThat(((Number)fleet.vehicle("tenant-demo",event.vin()).get("socPct")).doubleValue()).isCloseTo(value, within(0.000001)); }
  @Then("a moving vehicle generates a critical low-battery alert")
  public void critical() { assertThat(fleet.count("SELECT COUNT(*) FROM alerts WHERE tenant_id=? AND vin=? AND severity='CRITICAL'","tenant-demo",event.vin())).isPositive(); }
  @Then("the original event, mapping revision and approval are auditable")
  public void audit() { assertThat(fleet.count("SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND id=?","tenant-demo",event.eventId())).isEqualTo(1); assertThat(fleet.count("SELECT COUNT(*) FROM telemetry_revisions WHERE tenant_id=? AND event_id=?","tenant-demo",event.eventId())).isPositive(); assertThat(fleet.count("SELECT COUNT(*) FROM audit_log WHERE resource=? AND action='MAPPING_APPROVED'",mappingId)).isPositive(); }
  @Given("a viewer access token")
  public void viewer() { mappingId=(String)fleet.mappings("tenant-demo").getFirst().get("id"); }
  @When("the viewer requests mapping approval")
  public void deny() throws Exception { status=mvc.perform(post("/api/mappings/"+mappingId+"/approve").with(role("VIEWER")).contentType("application/json").content("{}")).andReturn().getResponse().getStatus(); }
  @Then("the API returns {int}")
  public void responseStatus(int expected) { assertThat(status).isEqualTo(expected); }
  @Given("an administrator with the matching tenant claim")
  public void admin() { vin=Simulator.vin(61); fleet.ingest("tenant-demo",simulator.event(60,800002,Instant.now(),"1",40.0)); }
  @When("the administrator confirms a vehicle VIN for erasure")
  public void erase() throws Exception { var result=mvc.perform(post("/api/privacy/erase").with(role("ADMIN")).contentType("application/json").content(json.writeValueAsString(Map.of("vin",vin,"confirmation",vin)))).andReturn().getResponse(); assertThat(result.getStatus()).isEqualTo(200); response=json.readTree(result.getContentAsString()); }
  @Then("its local telemetry, alerts, state and outbox are removed")
  public void removed() { for(String table:List.of("raw_events","alerts","vehicle_state","projection_outbox")) assertThat(fleet.count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND vin=?","tenant-demo",vin)).isZero(); }
  @Then("subsequent ingest for the removed vehicle is rejected")
  public void resurrection() { assertThatThrownBy(() -> fleet.ingest("tenant-demo",simulator.event(60,800003,Instant.now(),"1",42.0))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class); }
  @Then("external erasure remains pending until independently verified")
  public void external() { assertThat(response.get("externalVerificationRequired").asBoolean()).isTrue(); }
}
