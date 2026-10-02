package io.fleettruth;

import static org.assertj.core.api.Assertions.*;

import com.datastax.oss.driver.api.core.CqlSession;
import io.fleettruth.service.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"fleettruth.vehicles=8", "fleettruth.simulation=false",
  "spring.datasource.url=jdbc:h2:mem:cassandra;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker=true)
class CassandraProjectionIT {
  @Container static GenericContainer<?> cassandra = new GenericContainer<>("cassandra:5.0")
    .withEnv("MAX_HEAP_SIZE", "512M").withEnv("HEAP_NEWSIZE", "128M")
    .withExposedPorts(9042).waitingFor(Wait.forLogMessage(".*Starting listening for CQL clients.*\\n", 1))
    .withStartupTimeout(Duration.ofMinutes(5));

  @DynamicPropertySource static void settings(DynamicPropertyRegistry p) {
    p.add("fleettruth.cassandra", () -> true);
    p.add("fleettruth.cassandra-host", cassandra::getHost);
    p.add("fleettruth.cassandra-port", () -> cassandra.getMappedPort(9042));
  }

  @Autowired FleetService fleet;
  @Autowired Simulator simulator;
  @Autowired StorageProjection projection;

  @Test void durableOutboxProjectsCanonicalTelemetryWithTenantPartitionAndTtl() {
    var event = simulator.event(4, 500001, Instant.now(), "1", 63.0);
    assertThat(fleet.ingest("tenant-demo", event).status()).isEqualTo("ACCEPTED");
    try (var session = CqlSession.builder()
      .addContactPoint(new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
      .withLocalDatacenter("datacenter1").build()) {
      var query = session.prepare("SELECT normalized,TTL(normalized) AS remaining FROM fleettruth.telemetry WHERE tenant=? AND vin=? AND bucket=? AND event_time=? AND event_id=?");
      String bucket = event.eventTime().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
        projection.projectPending();
        var row = session.execute(query.bind("tenant-demo", event.vin(), bucket, event.eventTime(), event.eventId())).one();
        assertThat(row).isNotNull();
        assertThat(row.getString("normalized")).contains("\"socPct\":63.0");
        assertThat(row.getInt("remaining")).isBetween(86300, 86400);
      });
      assertThat(session.execute(query.bind("tenant-other", event.vin(), bucket, event.eventTime(), event.eventId())).one()).isNull();
      assertThat(fleet.count("SELECT COUNT(*) FROM projection_outbox WHERE tenant_id=? AND id LIKE ? AND status='DONE'", "tenant-demo", "tenant-demo:" + event.eventId() + ":%")).isEqualTo(1);
    }
  }
}
