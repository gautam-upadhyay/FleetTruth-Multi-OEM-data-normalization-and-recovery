package io.fleettruth;

import static org.assertj.core.api.Assertions.*;

import io.fleettruth.service.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.annotation.DirtiesContext
class PostgresIntegrationTest extends WorkflowTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    DockerImageName.parse(
      "pgvector/pgvector:0.8.6-pg17-bookworm"
    ).asCompatibleSubstituteFor("postgres")
  );

  @Container
  static GenericContainer<?> redis = new GenericContainer<>(
    DockerImageName.parse("redis:7.4-alpine")
  ).withExposedPorts(6379);

  @Container
  static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", postgres::getJdbcUrl);
    p.add("spring.datasource.username", postgres::getUsername);
    p.add("spring.datasource.password", postgres::getPassword);
    p.add("spring.data.redis.host", redis::getHost);
    p.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    p.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    p.add("fleettruth.kafka", () -> true);
    p.add("fleettruth.redis", () -> true);
    p.add("fleettruth.vector", () -> true);
  }

  @Autowired
  EventIntake intake;

  @Autowired
  StringRedisTemplate cache;

  @Autowired
  KnowledgeService knowledge;

  @Test
  void realBrokerProjectsToRealDatabaseAndCache() {
    var event = simulator.event(4, 200000, Instant.now(), "1", 56.0);
    assertThat(intake.accept("tenant-demo", event).status()).isEqualTo(
      "QUEUED"
    );
    org.awaitility.Awaitility.await()
      .atMost(Duration.ofSeconds(30))
      .until(
        () ->
          fleet.count(
            "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND id=? AND status='ACCEPTED'",
            "tenant-demo",
            event.eventId()
          ) == 1
      );
    org.awaitility.Awaitility.await()
      .atMost(Duration.ofSeconds(40))
      .until(() -> cache.hasKey("fleettruth:tenant-demo:" + event.vin()));
    assertThat(knowledge.storage()).isEqualTo("PGVECTOR_EXACT_COSINE");
    assertThat(knowledge.search("Helix soc_fraction")).hasSize(2);
  }
}
