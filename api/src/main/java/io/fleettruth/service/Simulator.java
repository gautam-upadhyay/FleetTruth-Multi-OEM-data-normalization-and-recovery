package io.fleettruth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.domain.Normalizer;
import io.fleettruth.domain.Telemetry.Event;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Simulator implements ApplicationRunner {

  public static final String TENANT = "tenant-demo";
  private final EventIntake intake;
  private final FleetService fleet;
  private final ObjectMapper json;
  private final boolean seed;
  private final int count;
  private final AtomicBoolean running;
  private final AtomicLong tick = new AtomicLong();
  private final AtomicInteger helixVersion = new AtomicInteger(2);
  private volatile boolean ready = false;
  public static final String[] OEMS = { "aster", "helix", "nord", "vertex" };

  public Simulator(
    FleetService fleet,
    EventIntake intake,
    ObjectMapper json,
    @Value("${fleettruth.seed}") boolean seed,
    @Value("${fleettruth.vehicles}") int count,
    @Value("${fleettruth.simulation}") boolean simulation
  ) {
    this.intake = intake;
    this.fleet = fleet;
    this.json = json;
    this.seed = seed;
    this.count = Math.max(4, Math.min(count, 1000000));
    running = new AtomicBoolean(simulation);
  }

  public static String vin(int n) {
    String base = "MFT" + String.format(Locale.ROOT, "%014d", n);
    int[] weights = { 8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2 };
    int sum = 0;
    for (int i = 0; i < 17; i++) {
      char c = base.charAt(i);
      int value = Character.isDigit(c)
        ? c - '0'
        : switch (c) {
            case 'M' -> 4;
            case 'F' -> 6;
            case 'T' -> 3;
            default -> 0;
          };
      sum += value * weights[i];
    }
    int check = sum % 11;
    return (
      base.substring(0, 8) +
      (check == 10 ? "X" : Integer.toString(check)) +
      base.substring(9)
    );
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!seed) {
      ready = true;
      return;
    }
    if (fleet.count("SELECT COUNT(*) FROM tenants") == 0) {
      fleet.db.update(
        "INSERT INTO tenants VALUES(?,?)",
        TENANT,
        "Meridian Logistics"
      );
      fleet.db.update(
        "INSERT INTO tenants VALUES(?,?)",
        "tenant-other",
        "Northstar Transport"
      );
      fleet.db.update(
        "INSERT INTO fleets VALUES(?,?,?,?)",
        "fleet-west",
        TENANT,
        "Western corridor",
        "Mumbai - Pune"
      );
      fleet.db.update(
        "INSERT INTO fleets VALUES(?,?,?,?)",
        "fleet-south",
        TENANT,
        "Southern corridor",
        "Bengaluru - Chennai"
      );
      fleet.db.update(
        "INSERT INTO fleets VALUES(?,?,?,?)",
        "fleet-other",
        "tenant-other",
        "Northstar fleet",
        "Delhi"
      );
      String[] names = {
          "Aster Motors",
          "Helix Automotive",
          "Nord Electric",
          "Vertex Mobility",
        },
        colors = { "#24876a", "#7761b5", "#d99a3e", "#4f83b9" };
      for (int j = 0; j < 4; j++) {
        fleet.db.update(
          "INSERT INTO oems VALUES(?,?,?)",
          OEMS[j],
          names[j],
          colors[j]
        );
        insertMapping(OEMS[j], "1", "APPROVED");
      }
      insertMapping("helix", "2", "DRAFT");
      String[] drivers = {
        "Aarav Mehta",
        "Priya Sharma",
        "Rohan Patel",
        "Ananya Rao",
        "Dev Shah",
        "Ishaan Nair",
        "Neha Verma",
        "Vikram Singh",
      };
      String[] models = {
        "Cargo E2",
        "Transit Pro",
        "N7 Electric",
        "Vantage EV",
      };
      for (String driver : drivers)
        fleet.db.update(
          "INSERT INTO drivers VALUES(?,?,?)",
          TENANT + ":" + driver,
          TENANT,
          driver
        );
      fleet.db.update(
        "INSERT INTO drivers VALUES(?,?,?)",
        "tenant-other:Synthetic driver",
        "tenant-other",
        "Synthetic driver"
      );
      for (int i = 0; i < 4; i++) fleet.db.update(
        "INSERT INTO vehicle_models VALUES(?,?,?,?)",
        OEMS[i] + ":" + models[i],
        OEMS[i],
        models[i],
        "EV"
      );
      for (int offset = 0; offset < count; offset += 1000) {
        var vehicles = new ArrayList<Object[]>();
        var states = new ArrayList<Object[]>();
        for (int i = offset; i < Math.min(count, offset + 1000); i++) {
          String vin = vin(i + 1),
            oem = OEMS[i % 4];
          vehicles.add(new Object[] {
            vin,
            TENANT,
            i % 3 == 0 ? "fleet-south" : "fleet-west",
            "MH " +
              String.format(Locale.ROOT, "%02d", (i % 48) + 1) +
              " FT " +
              String.format(Locale.ROOT, "%04d", i % 10000),
            oem + ":" + models[i % 4],
            TENANT + ":" + drivers[i % drivers.length],
          });
          states.add(new Object[] {
            vin,
            TENANT,
            FleetService.time(Instant.EPOCH),
            "unreported",
            "[]",
            "UNKNOWN",
          });
        }
        fleet.db.batchUpdate(
          "INSERT INTO vehicles(vin,tenant_id,fleet_id,registration,model_id,driver_id) VALUES(?,?,?,?,?,?)",
          vehicles
        );
        fleet.db.batchUpdate(
          "INSERT INTO vehicle_state(vin,tenant_id,event_time,event_id,dtc,quality) VALUES(?,?,?,?,?,?)",
          states
        );
      }
      fleet.db.update(
        "INSERT INTO vehicles(vin,tenant_id,fleet_id,registration,model_id,driver_id) VALUES(?,?,?,?,?,?)",
        vin(1000001),
        "tenant-other",
        "fleet-other",
        "DL 01 FT 0001",
        "aster:Cargo E2",
        "tenant-other:Synthetic driver"
      );
      fleet.db.update(
        "INSERT INTO vehicle_state(vin,tenant_id,event_time,event_id,dtc,quality) VALUES(?,?,?,?,?,?)",
        vin(1000001),
        "tenant-other",
        FleetService.time(Instant.EPOCH),
        "unreported",
        "[]",
        "UNKNOWN"
      );
      Instant now = Instant.now();
      for (int i = 0; i < 720; i++) {
        int index = i % Math.min(count, 192);
        Instant at = now.minusSeconds((720 - i) * 5L);
        Event e = event(index, i, at, "1", i % 61 == 0 ? 7.0 + (i % 7) : null);
        fleet.ingestAt(TENANT, e, at);
      }
      for (int i = 0; i < 24; i++) {
        int index = (1 + i * 4) % count;
        if (index % 4 != 1) continue;
        Event e = event(
          index,
          900 + i,
          now.minusSeconds(80 - i * 2L),
          "2",
          i % 4 == 0 ? 7.0 : 48.0
        );
        fleet.ingest(TENANT, e);
      }
      fleet.audit(
        TENANT,
        "system",
        "FLEET_SEEDED",
        "fleet-west",
        count + " synthetic vehicles enrolled; 4 OEM formats configured"
      );
    }
    var max = fleet.rows(
      "SELECT MAX(CAST(schema_version AS INTEGER)) AS version FROM mappings WHERE tenant_id=? AND oem_id='helix'",
      TENANT
    );
    if (
      !max.isEmpty() && max.getFirst().get("version") != null
    ) helixVersion.set(((Number) max.getFirst().get("version")).intValue());
    ready = true;
  }

  private String insertMapping(String oem, String schema, String status) {
    String id = FleetService.id();
    fleet.db.update(
      "INSERT INTO mappings(id,tenant_id,oem_id,schema_version,version,status,rules,created_at,approved_by,approved_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      id,
      TENANT,
      oem,
      schema,
      1,
      status,
      fleet.encode(Normalizer.rules(oem, schema)),
      FleetService.time(Instant.now()),
      status.equals("APPROVED") ? "system" : null,
      status.equals("APPROVED") ? FleetService.time(Instant.now()) : null
    );
    return id;
  }

  public Event event(
    int index,
    long sequence,
    Instant time,
    String schema,
    Double forcedSoc
  ) {
    String oem = OEMS[index % 4];
    double speed =
        Math.round((34 + (index % 46) + 8 * Math.sin(sequence / 9.0)) * 10) /
        10.0,
      soc =
        forcedSoc == null
          ? Math.max(17, 82 - (index % 47) - 5 * Math.sin(sequence / 40.0))
          : forcedSoc;
    double lat = 18.52 + (index % 12) * 0.009,
      lon = 73.80 + (index % 16) * 0.007,
      odo = 12800 + index * 12 + sequence / 30.0;
    List<String> codes = sequence % 73 == 0 ? List.of("P0301") : List.of();
    Map<String, Object> payload = new LinkedHashMap<>();
    switch (oem) {
      case "aster" -> {
        payload.put("speed_kmh", speed);
        payload.put("battery_pct", soc);
        payload.put("gps", Map.of("lat", lat, "lon", lon));
        payload.put("dtc", codes);
        payload.put("odometer_km", odo);
      }
      case "helix" -> {
        payload.put("velocity_mph", speed / 1.609344);
        payload.put(
          schema.equals("1") ? "state_of_charge" : "soc_fraction",
          schema.equals("1") ? soc : soc / 100
        );
        payload.put("gps", Map.of("lat", lat, "lon", lon));
        payload.put("dtc", codes);
        payload.put("odometer_km", odo);
      }
      case "nord" -> {
        payload.put("speed_mps", speed / 3.6);
        payload.put("battery_ratio", soc / 100);
        payload.put("distance_m", odo * 1000);
        payload.put("coordinates", List.of(lon, lat));
        payload.put("diagnostics", codes);
      }
      case "vertex" -> {
        payload.put("telemetry", Map.of("speed", speed));
        payload.put("power", Map.of("soc", soc));
        payload.put("location", Map.of("latitude", lat, "longitude", lon));
        payload.put("codes", codes);
        payload.put("odometer_km", odo);
      }
    }
    return new Event(
      FleetService.id(),
      vin(index + 1),
      oem,
      schema,
      time,
      sequence,
      json.valueToTree(payload)
    );
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 5000)
  public void simulate() {
    if (!ready || !running.get() || !seed) return;
    for (int j = 0; j < 24; j++) {
      long seq = tick.incrementAndGet();
      int index = (int) (seq % Math.min(count, 192));
      if (
        fleet.count(
          "SELECT COUNT(*) FROM vehicles WHERE tenant_id=? AND vin=?",
          TENANT,
          vin(index + 1)
        ) == 0
      ) continue;
      String version =
        index % 4 == 1 ? Integer.toString(helixVersion.get()) : "1";
      intake.accept(TENANT, event(index, seq, Instant.now(), version, null));
    }
  }

  public Map<String, Object> status() {
    return Map.of(
      "running",
      running.get(),
      "ready",
      ready,
      "enrolled",
      count,
      "targetEventsPerSecond",
      12,
      "generatedThisSession",
      tick.get(),
      "mode",
      "SYNTHETIC",
      "helixSchema",
      helixVersion.get()
    );
  }

  public Map<String, Object> toggle(boolean enabled) {
    running.set(enabled);
    return status();
  }

  public synchronized Map<String, Object> injectDrift(
    String tenant,
    String actor
  ) {
    if (
      !tenant.equals(TENANT) || !seed
    ) throw new org.springframework.web.server.ResponseStatusException(
      org.springframework.http.HttpStatus.FORBIDDEN,
      "Scenarios are available in the synthetic demo tenant"
    );
    if (
      fleet.count(
        "SELECT COUNT(*) FROM incidents WHERE tenant_id=? AND oem_id='helix' AND status='OPEN'",
        tenant
      ) > 0
    ) throw new org.springframework.web.server.ResponseStatusException(
      org.springframework.http.HttpStatus.CONFLICT,
      "Resolve the existing Helix incident before starting another"
    );
    int version = helixVersion.incrementAndGet();
    String mapping = insertMapping("helix", Integer.toString(version), "DRAFT");
    for (int i = 0; i < 24; i++) {
      int index = 1 + i * 4;
      if (index >= count) break;
      fleet.ingest(
        tenant,
        event(
          index,
          tick.incrementAndGet(),
          Instant.now(),
          Integer.toString(version),
          i % 4 == 0 ? 7.0 : 48.0
        )
      );
    }
    fleet.audit(
      tenant,
      actor,
      "SCENARIO_STARTED",
      mapping,
      "Helix schema " +
        version +
        " introduced; battery percentage changed to fractional units"
    );
    return Map.of(
      "mappingId",
      mapping,
      "schemaVersion",
      version,
      "status",
      "INJECTED"
    );
  }
}
