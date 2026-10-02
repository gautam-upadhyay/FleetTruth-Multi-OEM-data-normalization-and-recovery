package io.fleettruth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.fleettruth.domain.Telemetry.Event;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.*;

/** Synthetic broker load only; broker acknowledgements are not end-to-end SLO proof. */
public final class LoadGenerator {

  private LoadGenerator() {}

  public static void run() throws Exception {
    int rate = setting("EVENT_RATE", 1000),
      seconds = setting("DURATION_SECONDS", 60),
      shard = setting("GENERATOR_ID", 0),
      shards = setting("GENERATOR_SHARDS", 1),
      vehicles = setting("VEHICLES", 100000);
    if (
      rate < 1 ||
      seconds < 1 ||
      shards < 1 ||
      shard < 0 ||
      shard >= shards ||
      vehicles < 4 ||
      vehicles > 1000000
    ) throw new IllegalArgumentException("Invalid generator parameters");
    var config = new Properties();
    String file = System.getenv("KAFKA_PROPERTIES");
    if (file != null) try (var input = Files.newInputStream(Path.of(file))) {
      config.load(input);
    }
    config.put(
      "bootstrap.servers",
      System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    );
    config.put(
      "key.serializer",
      "org.apache.kafka.common.serialization.StringSerializer"
    );
    config.put(
      "value.serializer",
      "org.apache.kafka.common.serialization.StringSerializer"
    );
    config.put("acks", "all");
    config.put("enable.idempotence", "true");
    config.put("compression.type", "lz4");
    config.put("linger.ms", "5");
    config.put("batch.size", "65536");
    config.put("max.block.ms", "10000");
    config.put("delivery.timeout.ms", "30000");
    var json = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(
        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
      );
    var acknowledged = new LongAdder();
    var failed = new LongAdder();
    String run = UUID.randomUUID().toString();
    var random = new SplittableRandom(17L + shard);
    String[] vins = new String[vehicles];
    for (int i = 0; i < vehicles; i++) vins[i] = Simulator.vin(i + 1);
    String duplicate = null,
      duplicateKey = null;
    long sent = 0,
      start = System.nanoTime(),
      end = start + seconds * 1_000_000_000L;
    try (var producer = new KafkaProducer<String, String>(config)) {
      while (System.nanoTime() < end) {
        long elapsed = System.nanoTime() - start;
        long scheduled = (sent * 1_000_000_000L) / rate;
        if (scheduled > elapsed) {
          LockSupport.parkNanos(Math.min(scheduled - elapsed, 1_000_000));
          continue;
        }
        int index = (int) ((sent * shards + shard) % vehicles);
        String oem = Simulator.OEMS[index % 4];
        double trip = (sent / vehicles) % 120;
        double speed = trip < 10 ? 0 : 35 + random.nextDouble() * 50;
        double soc = Math.max(2, 88 - trip * .7 - (index % 12));
        boolean drift = oem.equals("helix") && sent % 997 == 0;
        if (sent % 211 == 0) soc = 140;
        Instant at = Instant.now().minusSeconds(sent % 53 == 0 ? 900 : 0);
        var payload = new LinkedHashMap<String, Object>();
        var dtc = sent % 157 == 0 ? List.of("P0301") : List.of();
        switch (oem) {
          case "aster" -> {
            payload.put("speed_kmh", speed);
            payload.put("battery_pct", soc);
            payload.put("gps", Map.of("lat", 18.52, "lon", 73.8));
            payload.put("dtc", dtc);
          }
          case "helix" -> {
            payload.put("velocity_mph", speed / 1.609344);
            payload.put(
              drift ? "soc_fraction" : "state_of_charge",
              drift ? soc / 100 : soc
            );
            payload.put("gps", Map.of("lat", 18.52, "lon", 73.8));
            payload.put("dtc", dtc);
          }
          case "nord" -> {
            payload.put("speed_mps", speed / 3.6);
            payload.put("battery_ratio", soc / 100);
            payload.put("coordinates", List.of(73.8, 18.52));
            payload.put("diagnostics", dtc);
          }
          default -> {
            payload.put("telemetry", Map.of("speed", speed));
            payload.put("power", Map.of("soc", soc));
            payload.put(
              "location",
              Map.of("latitude", 18.52, "longitude", 73.8)
            );
            payload.put("codes", dtc);
          }
        }
        var event = new Event(
          run + "-" + shard + "-" + sent,
          vins[index],
          oem,
          drift ? "unapproved-load" : "1",
          at,
          sent,
          json.valueToTree(payload)
        );
        String key = "tenant-demo:" + vins[index],
          value = json.writeValueAsString(
            new EventIntake.Envelope("tenant-demo", event)
          );
        if (sent % 97 == 0 && duplicate != null) {
          value = duplicate;
          key = duplicateKey;
        } else {
          duplicate = value;
          duplicateKey = key;
        }
        producer.send(
          new ProducerRecord<>("fleettruth.raw", key, value),
          (metadata, error) -> {
            if (error == null) acknowledged.increment();
            else failed.increment();
          }
        );
        sent++;
      }
      producer.flush();
    }
    var result = Map.of(
      "runId",
      run,
      "generator",
      shard,
      "vehicles",
      vehicles,
      "requestedEventsPerSecond",
      rate,
      "sent",
      sent,
      "brokerAcknowledged",
      acknowledged.sum(),
      "failed",
      failed.sum(),
      "elapsedSeconds",
      (System.nanoTime() - start) / 1e9,
      "scope",
      "Broker producer only; reconcile downstream IDs before claiming no loss"
    );
    System.out.println(json.writeValueAsString(result));
    if (failed.sum() > 0) throw new IllegalStateException(
      "Producer failures occurred"
    );
  }

  private static int setting(String name, int fallback) {
    return Integer.parseInt(
      System.getenv().getOrDefault(name, Integer.toString(fallback))
    );
  }
}
