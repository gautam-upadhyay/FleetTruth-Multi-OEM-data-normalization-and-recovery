package io.fleettruth.service;

import com.datastax.oss.driver.api.core.CqlSession;
import io.fleettruth.domain.Telemetry.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StorageProjection {

  public record Accepted(String tenant, Event event, Normalized normalized) {}

  private final boolean redisEnabled;
  private final StringRedisTemplate redis;
  private final FleetService fleet;
  private final TransactionTemplate tx;
  private CqlSession cassandra;
  private com.datastax.oss.driver.api.core.cql.PreparedStatement insertTelemetry;
  private final ProjectionCircuits circuits;

  public StorageProjection(
    StringRedisTemplate redis,
    FleetService fleet,
    TransactionTemplate tx,
    @Value("${fleettruth.redis}") boolean redisEnabled,
    @Value("${fleettruth.cassandra}") boolean enabled,
    @Value("${fleettruth.cassandra-host}") String host,
    @Value("${fleettruth.cassandra-port:9042}") int port,
    ProjectionCircuits circuits
  ) {
    this.redis = redis;
    this.fleet = fleet;
    this.tx = tx;
    this.redisEnabled = redisEnabled;
    this.circuits = circuits;
    if (enabled) {
      cassandra = CqlSession.builder()
        .addContactPoint(new InetSocketAddress(host, port))
        .withLocalDatacenter("datacenter1")
        .build();
      cassandra.execute(
        "CREATE KEYSPACE IF NOT EXISTS fleettruth WITH replication = {'class':'NetworkTopologyStrategy','datacenter1':1}"
      );
      cassandra.execute(
        "CREATE TABLE IF NOT EXISTS fleettruth.telemetry (tenant text, vin text, bucket text, event_time timestamp, event_id text, mapping_id text, normalized text, PRIMARY KEY ((tenant,vin,bucket),event_time,event_id)) WITH CLUSTERING ORDER BY (event_time DESC) AND default_time_to_live=86400"
      );
      insertTelemetry = cassandra.prepare("INSERT INTO fleettruth.telemetry (tenant,vin,bucket,event_time,event_id,mapping_id,normalized) VALUES(?,?,?,?,?,?,?)");
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(
    fixedDelay = 1000,
    initialDelay = 10000
  )
  public void projectPending() {
    var pending = fleet.rows(
      "SELECT id,payload,attempts FROM projection_outbox WHERE status='PENDING' AND available_at<=? ORDER BY available_at LIMIT 100",
      FleetService.time(Instant.now())
    );
    for (var row : pending) {
      try {
        tx.executeWithoutResult(status -> {
          var accepted = fleet.decode(
            (String) row.get("payload"),
            Accepted.class
          );
          if (
            fleet
              .rows(
                "SELECT vin FROM vehicles WHERE tenant_id=? AND vin=? FOR UPDATE",
                accepted.tenant(),
                accepted.event().vin()
              )
              .isEmpty()
          ) return;
          project(accepted);
          fleet.db.update(
            "UPDATE projection_outbox SET status='DONE' WHERE id=?",
            row.get("id")
          );
        });
      } catch (Exception ex) {
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        fleet.db.update(
          "UPDATE projection_outbox SET attempts=?,available_at=? WHERE id=?",
          attempts,
          FleetService.time(
            Instant.now().plusSeconds(Math.min(300, attempts * 5L))
          ),
          row.get("id")
        );
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "Projection will retry from durable outbox: {}",
          row.get("id")
        );
      }
    }
  }

  public void project(Accepted accepted) {
    String value = fleet.encode(accepted.normalized());
    var event = accepted.event();
    if (redisEnabled) {
      var script =
        new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
          "local t=redis.call('HGET',KEYS[1],'time'); local s=redis.call('HGET',KEYS[1],'sequence'); if not t or tonumber(t)<tonumber(ARGV[1]) or (tonumber(t)==tonumber(ARGV[1]) and tonumber(s or '0')<=tonumber(ARGV[2])) then redis.call('HSET',KEYS[1],'time',ARGV[1],'sequence',ARGV[2],'value',ARGV[3]); redis.call('EXPIRE',KEYS[1],86400); return 1 end; return 0",
          Long.class
        );
      circuits.execute("redis", () -> redis.execute(
        script,
        List.of("fleettruth:" + accepted.tenant() + ":" + event.vin()),
        Long.toString(event.eventTime().toEpochMilli()),
        Long.toString(event.sequence()),
        value
      ));
    }
    if (cassandra != null) {
      String bucket = event
        .eventTime()
        .atZone(ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
      circuits.execute("cassandra", () -> cassandra.execute(
        insertTelemetry.bind(
            accepted.tenant(),
            event.vin(),
            bucket,
            event.eventTime(),
            event.eventId(),
            accepted.normalized().mappingId(),
            value
          )
      ));
    }
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    if (cassandra != null) cassandra.close();
  }
}
