package io.fleettruth.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.domain.Normalizer;
import io.fleettruth.domain.Telemetry.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FleetService {

  public final JdbcTemplate db;
  private final ObjectMapper json;
  private final Normalizer normalizer;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private final TelemetryRollups rollups;
  private final org.springframework.context.ApplicationEventPublisher publisher;
  private final ConcurrentMap<String, Mapping> mappingCache =
    new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicLong> duplicates =
    new ConcurrentHashMap<>();

  public FleetService(
    JdbcTemplate db,
    ObjectMapper json,
    Normalizer normalizer,
    TransactionTemplate tx,
    MeterRegistry metrics,
    org.springframework.context.ApplicationEventPublisher publisher,
    TelemetryRollups rollups
  ) {
    this.db = db;
    this.json = json;
    this.normalizer = normalizer;
    this.tx = tx;
    this.metrics = metrics;
    this.publisher = publisher;
    this.rollups = rollups;
  }

  public String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode value", e);
    }
  }

  public <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored data", e);
    }
  }

  public List<Rule> rules(String value) {
    try {
      return json.readValue(value, new TypeReference<List<Rule>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid mapping rules", e);
    }
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static Timestamp time(Instant i) {
    return Timestamp.from(i.truncatedTo(ChronoUnit.MICROS));
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream().map(this::camel).toList();
  }

  private Map<String, Object> camel(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    row.forEach((k, v) -> {
      String[] parts = k.toLowerCase(Locale.ROOT).split("_");
      StringBuilder key = new StringBuilder(parts[0]);
      for (int i = 1; i < parts.length; i++) key
        .append(Character.toUpperCase(parts[i].charAt(0)))
        .append(parts[i].substring(1));
      if (v instanceof Timestamp t) v = t.toInstant().toString();
      if (v instanceof OffsetDateTime t) v = t.toInstant().toString();
      result.put(key.toString(), v);
    });
    return result;
  }

  public long count(String sql, Object... args) {
    Long n = db.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public void audit(
    String tenant,
    String actor,
    String action,
    String resource,
    String detail
  ) {
    db.update(
      "INSERT INTO audit_log VALUES(?,?,?,?,?,?,?)",
      id(),
      tenant,
      actor,
      action,
      resource,
      detail,
      time(Instant.now())
    );
  }

  public Mapping mapping(String tenant, String mappingId) {
    var found = rows(
      "SELECT * FROM mappings WHERE tenant_id=? AND id=?",
      tenant,
      mappingId
    );
    if (found.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Mapping not found"
    );
    return toMapping(found.getFirst());
  }

  private Mapping toMapping(Map<String, Object> row) {
    return new Mapping(
      (String) row.get("id"),
      (String) row.get("oemId"),
      (String) row.get("schemaVersion"),
      ((Number) row.get("version")).intValue(),
      (String) row.get("status"),
      rules((String) row.get("rules"))
    );
  }

  public Mapping activeMapping(String tenant, String oem, String schema) {
    String key = tenant + ":" + oem + ":" + schema;
    return mappingCache.computeIfAbsent(key, k -> {
      var found = rows(
        "SELECT * FROM mappings WHERE tenant_id=? AND oem_id=? AND schema_version=? AND status='APPROVED' ORDER BY version DESC LIMIT 1",
        tenant,
        oem,
        schema
      );
      return found.isEmpty() ? null : toMapping(found.getFirst());
    });
  }

  public void clearMappingCache() {
    mappingCache.clear();
  }

  public Intake ingest(String tenant, Event event) {
    return ingestAt(tenant, event, Instant.now());
  }

  // Only the local seed generator supplies historical receipt times.
  Intake ingestAt(String tenant, Event event, Instant received) {
    long start = System.nanoTime();
    try {
      return tx.execute(status -> {
        var vehicle = rows(
          "SELECT oem_id FROM vehicle_catalog WHERE tenant_id=? AND vin=?",
          tenant,
          event.vin()
        );
        if (
          vehicle.isEmpty() ||
          !vehicle.getFirst().get("oemId").equals(event.oem())
        ) throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Vehicle does not belong to this tenant and OEM"
        );
        if (
          db
            .queryForList(
              "SELECT vin FROM vehicles WHERE tenant_id=? AND vin=? FOR UPDATE",
              tenant,
              event.vin()
            )
            .isEmpty()
        ) throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Vehicle no longer enrolled"
        );
        db.update(
          "INSERT INTO raw_events(id,tenant_id,vin,oem_id,schema_version,event_time,received_at,sequence_no,payload,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
          event.eventId(),
          tenant,
          event.vin(),
          event.oem(),
          event.schemaVersion(),
          time(event.eventTime()),
          time(received),
          event.sequence(),
          encode(event.payload()),
          "PENDING"
        );
        Intake outcome = process(tenant, event, false);
        rollups.record(tenant, event.oem(), received, event.eventTime(), 1,
          outcome.status().equals("ACCEPTED") ? 1 : 0,
          outcome.status().equals("QUARANTINED") ? 1 : 0);
        metrics
          .counter("fleettruth_events_total", "status", outcome.status())
          .increment();
        return outcome;
      });
    } catch (DuplicateKeyException e) {
      if (
        count(
          "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND id=?",
          tenant,
          event.eventId()
        ) == 0
      ) throw e;
      duplicates
        .computeIfAbsent(tenant, key -> new AtomicLong())
        .incrementAndGet();
      metrics
        .counter("fleettruth_events_total", "status", "DUPLICATE")
        .increment();
      return new Intake(
        event.eventId(),
        "DUPLICATE",
        "This event has already been received"
      );
    } finally {
      metrics
        .timer("fleettruth_processing")
        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  private Intake process(String tenant, Event event, boolean replay) {
    if (
      replay &&
      db
        .queryForList(
          "SELECT vin FROM vehicles WHERE tenant_id=? AND vin=? FOR UPDATE",
          tenant,
          event.vin()
        )
        .isEmpty()
    ) return new Intake(
      event.eventId(),
      "ERASED",
      "Vehicle no longer enrolled"
    );
    Mapping mapping = activeMapping(tenant, event.oem(), event.schemaVersion());
    Result result = normalizer.normalize(event, mapping);
    if (!result.valid()) {
      String reason = String.join("; ", result.issues());
      db.update(
        "UPDATE raw_events SET status='QUARANTINED',reason=?,processed_at=? WHERE tenant_id=? AND id=?",
        reason,
        time(Instant.now()),
        tenant,
        event.eventId()
      );
      db.update(
        "UPDATE vehicle_state SET quality='UNTRUSTED' WHERE tenant_id=? AND vin=? AND event_time<=?",
        tenant,
        event.vin(),
        time(event.eventTime())
      );
      if (
        count(
          "SELECT COUNT(*) FROM incidents WHERE tenant_id=? AND oem_id=? AND schema_version=?",
          tenant,
          event.oem(),
          event.schemaVersion()
        ) == 0
      ) {
        db.update(
          "INSERT INTO incidents(id,tenant_id,oem_id,schema_version,title,description,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
          id(),
          tenant,
          event.oem(),
          event.schemaVersion(),
          "Schema change detected",
          reason,
          "OPEN",
          time(Instant.now())
        );
      }
      return new Intake(event.eventId(), "QUARANTINED", reason);
    }
    Normalized n = result.data();
    String encoded = encode(n);
    db.update(
      "UPDATE raw_events SET status='ACCEPTED',reason=NULL,mapping_id=?,normalized=?,processed_at=? WHERE tenant_id=? AND id=?",
      mapping.id(),
      encoded,
      time(Instant.now()),
      tenant,
      event.eventId()
    );
    if (
      count(
        "SELECT COUNT(*) FROM telemetry_revisions WHERE tenant_id=? AND event_id=? AND mapping_id=?",
        tenant,
        event.eventId(),
        mapping.id()
      ) == 0
    ) db.update(
      "INSERT INTO telemetry_revisions VALUES(?,?,?,?,?,?)",
      id(),
      tenant,
      event.eventId(),
      mapping.id(),
      encoded,
      time(Instant.now())
    );
    db.update(
      "UPDATE vehicle_state SET event_time=?,sequence_no=?,event_id=?,speed_kmh=?,soc_pct=?,odometer_km=?,latitude=?,longitude=?,dtc=?,quality=? WHERE tenant_id=? AND vin=? AND (event_time<? OR (event_time=? AND sequence_no<=?))",
      time(event.eventTime()),
      event.sequence(),
      event.eventId(),
      n.speedKmh(),
      n.socPct(),
      n.odometerKm(),
      n.latitude(),
      n.longitude(),
      encode(n.dtc()),
      n.quality(),
      tenant,
      event.vin(),
      time(event.eventTime()),
      time(event.eventTime()),
      event.sequence()
    );
    db.update(
      "UPDATE vehicle_state SET quality='UNTRUSTED' WHERE tenant_id=? AND vin=? AND EXISTS(SELECT 1 FROM raw_events r WHERE r.tenant_id=vehicle_state.tenant_id AND r.vin=vehicle_state.vin AND r.status='QUARANTINED' AND r.event_time>=vehicle_state.event_time)",
      tenant,
      event.vin()
    );
    if (
      n.socPct() != null &&
      n.socPct() <= 15 &&
      n.speedKmh() != null &&
      n.speedKmh() > 0
    ) alert(
      tenant,
      event,
      "LOW_BATTERY",
      n.socPct() <= 8 ? "CRITICAL" : "WARNING",
      "Low battery while moving",
      String.format(
        Locale.ROOT,
        "Battery is %.1f%% at %.1f km/h. Review the assigned route and charging plan.",
        n.socPct(),
        n.speedKmh()
      )
    );
    if (!n.dtc().isEmpty()) alert(
      tenant,
      event,
      "DIAGNOSTIC",
      "WARNING",
      "Diagnostic fault reported",
      "Vehicle reported " +
        String.join(", ", n.dtc()) +
        ". Review the diagnostic evidence and service policy."
    );
    String projectionId = tenant + ":" + event.eventId() + ":" + mapping.id();
    if (
      count(
        "SELECT COUNT(*) FROM projection_outbox WHERE id=?",
        projectionId
      ) == 0
    ) db.update(
      "INSERT INTO projection_outbox VALUES(?,?,?,?,?,?,?,?)",
      projectionId,
      tenant,
      event.vin(),
      encode(new StorageProjection.Accepted(tenant, event, n)),
      "PENDING",
      0,
      time(Instant.now()),
      time(Instant.now())
    );
    return new Intake(event.eventId(), replay ? "RECOVERED" : "ACCEPTED", null);
  }

  private void alert(
    String tenant,
    Event event,
    String kind,
    String severity,
    String title,
    String description
  ) {
    if (
      count(
        "SELECT COUNT(*) FROM alerts WHERE tenant_id=? AND event_id=? AND kind=?",
        tenant,
        event.eventId(),
        kind
      ) > 0
    ) return;
    // Repeated samples in one episode update evidence without flooding the operator.
    var open = rows(
      "SELECT a.id,r.event_time,r.sequence_no FROM alerts a JOIN raw_events r ON r.tenant_id=a.tenant_id AND r.id=a.event_id WHERE a.tenant_id=? AND a.vin=? AND a.kind=? AND a.status IN ('OPEN','ACKNOWLEDGED') ORDER BY a.created_at DESC LIMIT 1",
      tenant,
      event.vin(),
      kind
    );
    if (!open.isEmpty()) {
      var latest = open.getFirst();
      Instant at = Instant.parse((String) latest.get("eventTime")),
        incoming = event.eventTime().truncatedTo(ChronoUnit.MICROS);
      if (
        incoming.isBefore(at) ||
        (incoming.equals(at) &&
          event.sequence() < ((Number) latest.get("sequenceNo")).longValue())
      ) return;
    }
    if (!open.isEmpty()) {
      db.update(
        "UPDATE alerts SET description=?,event_id=?,severity=?,updated_at=? WHERE tenant_id=? AND id=?",
        description,
        event.eventId(),
        severity,
        time(Instant.now()),
        tenant,
        open.getFirst().get("id")
      );
      return;
    }
    db.update(
      "INSERT INTO alerts(id,tenant_id,vin,event_id,oem_id,kind,severity,title,description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      id(),
      tenant,
      event.vin(),
      event.eventId(),
      event.oem(),
      kind,
      severity,
      title,
      description,
      "OPEN",
      time(event.eventTime()),
      time(Instant.now())
    );
  }

  public Map<String, Object> overview(String tenant) {
    var sources = oems(tenant);
    long total = 0,
      accepted = 0,
      quarantined = 0,
      vehicles = 0;
    for (var source : sources) {
      total += ((Number) source.get("events")).longValue();
      accepted += ((Number) source.get("accepted")).longValue();
      quarantined += ((Number) source.get("quarantined")).longValue();
      vehicles += ((Number) source.get("vehicles")).longValue();
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("vehicles", vehicles);
    out.put(
      "reporting",
      count(
        "SELECT COUNT(*) FROM vehicle_state WHERE tenant_id=? AND event_time>?",
        tenant,
        time(Instant.now().minusSeconds(300))
      )
    );
    out.put("processed", total);
    out.put("accepted", accepted);
    out.put("quarantined", quarantined);
    out.put(
      "quality",
      total == 0 ? 100 : Math.round((accepted * 10000.0) / total) / 100.0
    );
    out.put(
      "duplicates",
      duplicates.getOrDefault(tenant, new AtomicLong()).get()
    );
    out.put(
      "openAlerts",
      count(
        "SELECT COUNT(*) FROM alerts WHERE tenant_id=? AND status<>'RESOLVED'",
        tenant
      )
    );
    out.put(
      "criticalAlerts",
      count(
        "SELECT COUNT(*) FROM alerts WHERE tenant_id=? AND severity='CRITICAL' AND status<>'RESOLVED'",
        tenant
      )
    );
    out.put(
      "openIncidents",
      count(
        "SELECT COUNT(*) FROM incidents WHERE tenant_id=? AND status='OPEN'",
        tenant
      )
    );
    out.put(
      "eventsPerSecond",
      Math.round(
        (count(
          "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND received_at>?",
          tenant,
          time(Instant.now().minusSeconds(60))
        ) /
          60.0) *
          10
      ) / 10.0
    );
    out.put("timestamp", Instant.now());
    out.put("series", series(tenant));
    out.put("oems", sources);
    out.put(
      "incidents",
      rows(
        "SELECT i.*,o.name AS oem_name FROM incidents i JOIN oems o ON i.oem_id=o.id WHERE tenant_id=? ORDER BY created_at DESC LIMIT 8",
        tenant
      )
    );
    out.put("alerts", alerts(tenant, "ALL", 8));
    return out;
  }

  public List<Map<String, Object>> series(String tenant) {
    Instant start = Instant.now()
      .minusSeconds(3600)
      .truncatedTo(ChronoUnit.MINUTES);
    var buckets = new TreeMap<Long, long[]>();
    for (int i = 0; i <= 60; i++) buckets.put(
      start.plusSeconds(i * 60L).getEpochSecond(),
      new long[2]
    );
    db.query(
      "SELECT bucket,SUM(accepted),SUM(quarantined) FROM telemetry_rollups WHERE tenant_id=? AND kind='MINUTE' AND bucket>=? AND bucket<? GROUP BY bucket",
      rs -> {
        long key = rs
          .getTimestamp(1)
          .toInstant()
          .truncatedTo(ChronoUnit.MINUTES)
          .getEpochSecond();
        long[] b = buckets.get(key);
        if (b != null) {
          b[0] = rs.getLong(2);
          b[1] = rs.getLong(3);
        }
      },
      tenant,
      time(start),
      time(start.plusSeconds(61 * 60L))
    );
    return buckets
      .entrySet()
      .stream()
      .map(e -> {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("time", Instant.ofEpochSecond(e.getKey()).toString());
        point.put("accepted", e.getValue()[0]);
        point.put("quarantined", e.getValue()[1]);
        return point;
      })
      .toList();
  }

  public List<Map<String, Object>> oems(String tenant) {
    var eventCounts = new HashMap<String, Map<String, Object>>();
    for (var row : rows(
      "SELECT oem_id,total,accepted,quarantined AS bad,last_seen FROM telemetry_rollups WHERE tenant_id=? AND kind='TOTAL'",
      tenant
    ))
      eventCounts.put((String) row.get("oemId"), row);
    var vehicleCounts = new HashMap<String, Long>();
    for (var row : rows(
      "SELECT m.oem_id,SUM(c.total) AS total FROM (SELECT model_id,COUNT(*) AS total FROM vehicles WHERE tenant_id=? GROUP BY model_id) c JOIN vehicle_models m ON m.id=c.model_id GROUP BY m.oem_id",
      tenant
    ))
      vehicleCounts.put(
        (String) row.get("oemId"),
        ((Number) row.get("total")).longValue()
      );
    var items = rows("SELECT * FROM oems ORDER BY id");
    for (var item : items) {
      String oem = (String) item.get("id");
      var counts = eventCounts.get(oem);
      long total =
          counts == null ? 0 : ((Number) counts.get("total")).longValue(),
        bad = counts == null ? 0 : ((Number) counts.get("bad")).longValue();
      item.put("vehicles", vehicleCounts.getOrDefault(oem, 0L));
      item.put("events", total);
      long accepted =
        counts == null ? 0 : ((Number) counts.get("accepted")).longValue();
      item.put("accepted", accepted);
      item.put("quarantined", bad);
      item.put(
        "quality",
        total == 0 ? 100 : Math.round((accepted * 10000.0) / total) / 100.0
      );
      item.put("status", bad > 0 ? "DEGRADED" : "HEALTHY");
      item.put("lastSeen", counts == null ? null : counts.get("lastSeen"));
    }
    return items;
  }

  public Map<String, Object> vehicles(
    String tenant,
    String search,
    String oem,
    String cursor,
    int limit
  ) {
    limit = Math.max(1, Math.min(limit, 100));
    String term = search
      .toUpperCase(Locale.ROOT)
      .replace("%", "")
      .replace("_", "")
      .trim();
    var args = new ArrayList<Object>();
    args.add(tenant);
    String where = " WHERE v.tenant_id=?";
    if (!term.isEmpty()) {
      where +=
        " AND (v.vin LIKE ? OR UPPER(v.registration) LIKE ? OR EXISTS(SELECT 1 FROM drivers d WHERE d.id=v.driver_id AND UPPER(d.name) LIKE ?))";
      args.add("%" + term + "%");
      args.add("%" + term + "%");
      args.add("%" + term + "%");
    }
    if (!oem.isBlank()) {
      where +=
        " AND v.model_id IN (SELECT id FROM vehicle_models WHERE oem_id=?)";
      args.add(oem);
    }
    long total = count(
      "SELECT COUNT(*) FROM vehicles v" + where,
      args.toArray()
    );
    args.add(cursor);
    args.add(limit + 1);
    // Restrict the vehicle set before dimension/state joins, not after a 100K-row catalog join.
    var items = rows(
      "SELECT v.*,m.oem_id,m.name AS model,m.powertrain,d.name AS driver_name,o.name AS oem_name,o.color AS oem_color,f.name AS fleet_name,s.event_time,s.speed_kmh,s.soc_pct,s.quality,s.latitude,s.longitude FROM (SELECT * FROM vehicles v" +
        where +
        " AND v.vin>? ORDER BY v.tenant_id,v.vin LIMIT ?) v JOIN vehicle_models m ON m.id=v.model_id JOIN oems o ON o.id=m.oem_id JOIN fleets f ON f.id=v.fleet_id LEFT JOIN drivers d ON d.id=v.driver_id LEFT JOIN vehicle_state s ON s.vin=v.vin AND s.tenant_id=v.tenant_id ORDER BY v.vin",
      args.toArray()
    );
    boolean more = items.size() > limit;
    var visible = items.subList(0, Math.min(items.size(), limit));
    return Map.of(
      "items",
      visible,
      "total",
      total,
      "nextCursor",
      more ? visible.getLast().get("vin") : ""
    );
  }

  public Map<String, Object> vehicle(String tenant, String vin) {
    var list = rows(
      "SELECT v.*,o.name AS oem_name,f.name AS fleet_name,s.event_time,s.speed_kmh,s.soc_pct,s.odometer_km,s.latitude,s.longitude,s.quality,s.dtc FROM vehicle_catalog v JOIN oems o ON o.id=v.oem_id JOIN fleets f ON f.id=v.fleet_id LEFT JOIN vehicle_state s ON s.vin=v.vin WHERE v.tenant_id=? AND v.vin=?",
      tenant,
      vin
    );
    if (list.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Vehicle not found"
    );
    var out = list.getFirst();
    out.put(
      "events",
      rows(
        "SELECT id,oem_id,event_time,status,reason,mapping_id,payload,normalized FROM raw_events WHERE tenant_id=? AND vin=? ORDER BY event_time DESC LIMIT 40",
        tenant,
        vin
      )
    );
    out.put(
      "alerts",
      rows(
        "SELECT * FROM alerts WHERE tenant_id=? AND vin=? ORDER BY created_at DESC LIMIT 20",
        tenant,
        vin
      )
    );
    return out;
  }

  public List<Map<String, Object>> alerts(
    String tenant,
    String status,
    int limit
  ) {
    return rows(
      "SELECT a.*,v.registration,v.model,o.name AS oem_name,o.color AS oem_color FROM alerts a JOIN vehicle_catalog v ON v.vin=a.vin JOIN oems o ON o.id=a.oem_id WHERE a.tenant_id=? AND (?='ALL' OR a.status=?) ORDER BY CASE WHEN a.status='RESOLVED' THEN 1 ELSE 0 END,CASE WHEN a.severity='CRITICAL' THEN 0 ELSE 1 END,a.created_at DESC LIMIT ?",
      tenant,
      status,
      status,
      Math.min(200, Math.max(1, limit))
    );
  }

  public void updateAlert(
    String tenant,
    String actor,
    String alertId,
    String status
  ) {
    if (
      !Set.of("ACKNOWLEDGED", "RESOLVED", "OPEN").contains(status)
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Unsupported alert state"
    );
    if (
      db.update(
        "UPDATE alerts SET status=?,assigned_to=?,updated_at=? WHERE tenant_id=? AND id=?",
        status,
        actor,
        time(Instant.now()),
        tenant,
        alertId
      ) == 0
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Alert not found"
    );
    audit(tenant, actor, "ALERT_" + status, alertId, "Alert state updated");
  }

  public List<Map<String, Object>> mappings(String tenant) {
    var list = rows(
      "SELECT m.*,o.name AS oem_name,o.color AS oem_color FROM mappings m JOIN oems o ON o.id=m.oem_id WHERE m.tenant_id=? ORDER BY CASE WHEN status='DRAFT' THEN 0 ELSE 1 END,m.created_at DESC",
      tenant
    );
    for (var row : list) row.put("rules", rules((String) row.get("rules")));
    return list;
  }

  public Map<String, Object> preview(
    String tenant,
    String mappingId,
    List<Rule> override
  ) {
    Mapping existing = mapping(tenant, mappingId);
    Mapping proposed =
      override == null
        ? existing
        : new Mapping(
            existing.id(),
            existing.oem(),
            existing.schemaVersion(),
            existing.version(),
            existing.status(),
            override
          );
    var samples = rows(
      "SELECT * FROM raw_events WHERE tenant_id=? AND oem_id=? AND schema_version=? ORDER BY received_at DESC LIMIT 20",
      tenant,
      existing.oem(),
      existing.schemaVersion()
    );
    var results = new ArrayList<Map<String, Object>>();
    int valid = 0;
    for (var sample : samples) {
      Event event = event(sample);
      Result result = normalizer.normalize(event, proposed);
      if (result.valid()) valid++;
      var row = new LinkedHashMap<String, Object>();
      row.put("eventId", event.eventId());
      row.put("vin", event.vin());
      row.put("raw", event.payload());
      row.put("normalized", result.data());
      row.put("valid", result.valid());
      row.put("issues", result.issues());
      results.add(row);
    }
    return Map.of(
      "samples",
      results,
      "total",
      samples.size(),
      "valid",
      valid,
      "invalid",
      samples.size() - valid,
      "rules",
      proposed.rules()
    );
  }

  public void approve(
    String tenant,
    String actor,
    String mappingId,
    List<Rule> override
  ) {
    tx.executeWithoutResult(txStatus -> {
      db.queryForList(
        "SELECT id FROM mappings WHERE tenant_id=? AND id=? FOR UPDATE",
        tenant,
        mappingId
      );
      Mapping m = mapping(tenant, mappingId);
      if (!m.status().equals("DRAFT")) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Only draft mappings can be approved"
      );
      var preview = preview(tenant, mappingId, override);
      if (
        (int) preview.get("total") == 0 || (int) preview.get("invalid") > 0
      ) throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "A mapping must pass its sample validation before approval"
      );
      List<Rule> updated = override == null ? m.rules() : override;
      db.update(
        "UPDATE mappings SET status='SUPERSEDED' WHERE tenant_id=? AND oem_id=? AND schema_version=? AND status='APPROVED'",
        tenant,
        m.oem(),
        m.schemaVersion()
      );
      db.update(
        "UPDATE mappings SET status='APPROVED',rules=?,approved_by=?,approved_at=? WHERE tenant_id=? AND id=?",
        encode(updated),
        actor,
        time(Instant.now()),
        tenant,
        mappingId
      );
      audit(
        tenant,
        actor,
        "MAPPING_APPROVED",
        mappingId,
        "Approved version " +
          m.version() +
          " for " +
          m.oem() +
          " schema " +
          m.schemaVersion()
      );
    });
    clearMappingCache();
  }

  public Map<String, Object> startReplay(
    String tenant,
    String actor,
    String oem
  ) {
    if (
      !Set.of("aster", "helix", "nord", "vertex").contains(oem)
    ) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown OEM");
    String jobId = id();
    Instant started = Instant.now();
    tx.executeWithoutResult(status -> {
      db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
      if (
        count(
          "SELECT COUNT(*) FROM replay_jobs WHERE tenant_id=? AND oem_id=? AND status='RUNNING'",
          tenant,
          oem
        ) > 0
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "A replay is already running"
      );
      long total = count(
        "SELECT COUNT(*) FROM raw_events WHERE tenant_id=? AND oem_id=? AND status='QUARANTINED' AND received_at<=?",
        tenant,
        oem,
        time(started)
      );
      if (total == 0) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "There are no quarantined events to replay"
      );
      db.update(
        "INSERT INTO replay_jobs(id,tenant_id,oem_id,status,total,recovered,failed,requested_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        jobId,
        tenant,
        oem,
        "RUNNING",
        total,
        0,
        0,
        actor,
        time(started)
      );
      audit(
        tenant,
        actor,
        "REPLAY_STARTED",
        jobId,
        "Replay requested for " + oem
      );
    });
    return Map.of("id", jobId, "status", "RUNNING");
  }

  public Event event(Map<String, Object> row) {
    return new Event(
      (String) row.get("id"),
      (String) row.get("vin"),
      (String) row.get("oemId"),
      (String) row.get("schemaVersion"),
      Instant.parse((String) row.get("eventTime")),
      ((Number) row.get("sequenceNo")).longValue(),
      decode(
        (String) row.get("payload"),
        com.fasterxml.jackson.databind.JsonNode.class
      )
    );
  }

  public Map<String, Object> eventDetail(String tenant, String eventId) {
    var found = rows(
      "SELECT * FROM raw_events WHERE tenant_id=? AND id=?",
      tenant,
      eventId
    );
    if (found.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Event not found"
    );
    var row = found.getFirst();
    row.put(
      "payload",
      decode(
        (String) row.get("payload"),
        com.fasterxml.jackson.databind.JsonNode.class
      )
    );
    if (row.get("normalized") != null) row.put(
      "normalized",
      decode(
        (String) row.get("normalized"),
        com.fasterxml.jackson.databind.JsonNode.class
      )
    );
    row.put(
      "revisions",
      rows(
        "SELECT mapping_id,normalized,created_at FROM telemetry_revisions WHERE tenant_id=? AND event_id=? ORDER BY created_at",
        tenant,
        eventId
      )
    );
    return row;
  }

  public Map<String, Object> analytics(String tenant) {
    return Map.of(
      "series",
      series(tenant),
      "oems",
      oems(tenant),
      "replays",
      rows(
        "SELECT * FROM replay_jobs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
        tenant
      ),
      "daily",
      rows(
        "SELECT CAST(bucket AT TIME ZONE 'UTC' AS DATE) AS \"day\",SUM(total) AS total,SUM(accepted) AS accepted,SUM(quarantined) AS quarantined FROM telemetry_rollups WHERE tenant_id=? AND kind='DAY' GROUP BY bucket ORDER BY bucket DESC LIMIT 30",
        tenant
      )
    );
  }

  Intake replayEvent(String tenant, Event event) {
    var before = rows("SELECT status,received_at FROM raw_events WHERE tenant_id=? AND id=?", tenant, event.eventId());
    var result = process(tenant, event, true);
    if (!before.isEmpty() && result.status().equals("RECOVERED") && !before.getFirst().get("status").equals("ACCEPTED")) {
      rollups.record(tenant, event.oem(), Instant.parse((String) before.getFirst().get("receivedAt")), event.eventTime(), 0, 1,
        before.getFirst().get("status").equals("QUARANTINED") ? -1 : 0);
    }
    return result;
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
  public void refreshMappings() {
    clearMappingCache();
  }
}
