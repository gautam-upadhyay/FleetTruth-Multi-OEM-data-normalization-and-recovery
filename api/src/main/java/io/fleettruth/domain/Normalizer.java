package io.fleettruth.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.fleettruth.domain.Telemetry.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Normalizer {

  private static final Set<String> FIELDS = Set.of(
    "speedKmh",
    "socPct",
    "odometerKm",
    "latitude",
    "longitude",
    "dtc"
  );

  public Result normalize(Event event, Mapping mapping) {
    var issues = new ArrayList<String>();
    if (!VinValidator.valid(event.vin())) issues.add(
      "Invalid VIN: expected 17 characters and a valid check digit"
    );
    if (
      event.eventTime() == null ||
      event.eventTime().isAfter(Instant.now().plusSeconds(120))
    ) issues.add("Event timestamp is missing or in the future");
    if (event.payload() == null || !event.payload().isObject()) issues.add(
      "Payload must be an object"
    );
    if (mapping == null) return new Result(
      false,
      null,
      List.of(
        "Unrecognized schema version " +
          event.schemaVersion() +
          "; an approved mapping is required"
      )
    );
    if (
      !mapping.oem().equals(event.oem()) ||
      !mapping.schemaVersion().equals(event.schemaVersion())
    ) issues.add("Mapping does not match OEM and schema version");
    if (!issues.isEmpty()) return new Result(false, null, issues);
    if (mapping.rules() == null || mapping.rules().isEmpty()) return new Result(
      false,
      null,
      List.of("A mapping must define canonical signals")
    );
    var required = Set.of("speedKmh", "socPct", "latitude", "longitude");
    var seen = new HashSet<String>();
    for (var rule : mapping.rules()) {
      if (rule == null || rule.field() == null) return new Result(
        false,
        null,
        List.of("Mapping rules and canonical fields cannot be null")
      );
      if (!seen.add(rule.field())) issues.add(
        "Duplicate canonical signal: " + rule.field()
      );
      if (required.contains(rule.field()) && !rule.required()) issues.add(
        "Canonical signal must be required: " + rule.field()
      );
    }
    if (!seen.containsAll(required)) issues.add(
      "Mapping must include speed, battery, latitude and longitude"
    );
    if (!issues.isEmpty()) return new Result(false, null, issues);
    var values = new HashMap<String, Double>();
    var dtc = new ArrayList<String>();
    for (var rule : mapping.rules()) {
      if (
        !FIELDS.contains(rule.field()) ||
        rule.source() == null ||
        !rule.source().startsWith("/") ||
        !Double.isFinite(rule.scale()) ||
        !Double.isFinite(rule.offset())
      ) {
        issues.add("Invalid mapping rule: " + rule.field());
        continue;
      }
      JsonNode value;
      try {
        value = event.payload().at(rule.source());
      } catch (IllegalArgumentException ex) {
        issues.add("Invalid JSON pointer for " + rule.field());
        continue;
      }
      if (value.isMissingNode() || value.isNull()) {
        if (rule.required()) issues.add(
          "Missing required signal: " + rule.source()
        );
        continue;
      }
      if (rule.field().equals("dtc")) {
        if (!value.isArray()) {
          issues.add("Diagnostic codes must be an array");
          continue;
        }
        for (var code : value) {
          if (
            !code.isTextual() ||
            !code.asText().matches("[PCBU][0-3][0-9A-F]{3}")
          ) issues.add("Invalid diagnostic code");
          else dtc.add(code.asText());
        }
      } else {
        if (!value.isNumber()) {
          issues.add("Expected a number at " + rule.source());
          continue;
        }
        double number = value.asDouble() * rule.scale() + rule.offset();
        if (!Double.isFinite(number)) {
          issues.add("Non-finite signal: " + rule.field());
          continue;
        }
        values.put(rule.field(), number);
      }
    }
    range(values, "socPct", 0, 100, issues);
    range(values, "speedKmh", 0, 350, issues);
    range(values, "odometerKm", 0, 5000000, issues);
    range(values, "latitude", -90, 90, issues);
    range(values, "longitude", -180, 180, issues);
    if (!issues.isEmpty()) return new Result(false, null, issues);
    String quality = event.eventTime().isBefore(Instant.now().minusSeconds(120))
      ? "STALE"
      : "VALID";
    return new Result(
      true,
      new Normalized(
        values.get("speedKmh"),
        values.get("socPct"),
        values.get("odometerKm"),
        values.get("latitude"),
        values.get("longitude"),
        dtc,
        quality,
        mapping.id(),
        event.eventTime()
      ),
      List.of()
    );
  }

  private void range(
    Map<String, Double> values,
    String key,
    double min,
    double max,
    List<String> issues
  ) {
    Double n = values.get(key);
    if (n != null && (n < min || n > max)) issues.add(
      key + " outside allowed range [" + min + ", " + max + "]"
    );
  }

  public static List<Rule> rules(String oem, String version) {
    String speed = "/speed_kmh",
      soc = "/battery_pct",
      odo = "/odometer_km",
      lat = "/gps/lat",
      lon = "/gps/lon",
      dtc = "/dtc";
    double speedScale = 1,
      socScale = 1,
      odoScale = 1;
    switch (oem) {
      case "helix" -> {
        speed = "/velocity_mph";
        speedScale = 1.609344;
        soc = version.equals("1") ? "/state_of_charge" : "/soc_fraction";
        socScale = version.equals("1") ? 1 : 100;
      }
      case "nord" -> {
        speed = "/speed_mps";
        speedScale = 3.6;
        soc = "/battery_ratio";
        socScale = 100;
        odo = "/distance_m";
        odoScale = 0.001;
        lat = "/coordinates/1";
        lon = "/coordinates/0";
        dtc = "/diagnostics";
      }
      case "vertex" -> {
        speed = "/telemetry/speed";
        soc = "/power/soc";
        lat = "/location/latitude";
        lon = "/location/longitude";
        dtc = "/codes";
      }
      default -> {
      }
    }
    return List.of(
      new Rule("speedKmh", speed, speedScale, 0, true),
      new Rule("socPct", soc, socScale, 0, true),
      new Rule("odometerKm", odo, odoScale, 0, false),
      new Rule("latitude", lat, 1, 0, true),
      new Rule("longitude", lon, 1, 0, true),
      new Rule("dtc", dtc, 1, 0, false)
    );
  }
}
