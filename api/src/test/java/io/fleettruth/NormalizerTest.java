package io.fleettruth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.domain.Normalizer;
import io.fleettruth.domain.Telemetry.*;
import io.fleettruth.service.Simulator;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NormalizerTest {

  private final Normalizer normalizer = new Normalizer();
  private final ObjectMapper json = new ObjectMapper();

  private Event event(String oem, String schema, String payload)
    throws Exception {
    return new Event(
      "event-1",
      Simulator.vin(1),
      oem,
      schema,
      Instant.now(),
      1,
      json.readTree(payload)
    );
  }

  private Mapping mapping(String oem, String schema) {
    return new Mapping(
      "mapping",
      oem,
      schema,
      1,
      "APPROVED",
      Normalizer.rules(oem, schema)
    );
  }

  @Test
  void convertsMphAndFractionalBatteryWithoutGuessing() throws Exception {
    var result = normalizer.normalize(
      event(
        "helix",
        "2",
        "{\"velocity_mph\":40,\"soc_fraction\":0.09,\"gps\":{\"lat\":18.52,\"lon\":73.8},\"dtc\":[]}"
      ),
      mapping("helix", "2")
    );
    assertThat(result.valid()).isTrue();
    assertThat(result.data().speedKmh()).isCloseTo(64.37376, within(.00001));
    assertThat(result.data().socPct()).isEqualTo(9);
  }

  @Test
  void rejectsUnknownSchemaInsteadOfReusingOldMapping() throws Exception {
    var e = event("helix", "2", "{}");
    assertThat(normalizer.normalize(e, null).valid()).isFalse();
    assertThat(
      normalizer.normalize(e, mapping("helix", "1")).issues()
    ).contains("Mapping does not match OEM and schema version");
  }

  @Test
  void convertsNordCoordinatesAndDistances() throws Exception {
    var result = normalizer.normalize(
      event(
        "nord",
        "1",
        "{\"speed_mps\":10,\"battery_ratio\":0.7,\"distance_m\":5000,\"coordinates\":[73.8,18.52],\"diagnostics\":[\"P0301\"]}"
      ),
      mapping("nord", "1")
    );
    assertThat(result.valid()).isTrue();
    assertThat(result.data().speedKmh()).isEqualTo(36);
    assertThat(result.data().socPct()).isEqualTo(70);
    assertThat(result.data().odometerKm()).isEqualTo(5);
    assertThat(result.data().latitude()).isEqualTo(18.52);
    assertThat(result.data().dtc()).containsExactly("P0301");
  }

  @Test
  void optionalMissingSignalStaysNull() throws Exception {
    var result = normalizer.normalize(
      event(
        "aster",
        "1",
        "{\"speed_kmh\":0,\"battery_pct\":0,\"gps\":{\"lat\":0,\"lon\":0}}"
      ),
      mapping("aster", "1")
    );
    assertThat(result.valid()).isTrue();
    assertThat(result.data().socPct()).isZero();
    assertThat(result.data().odometerKm()).isNull();
    assertThat(result.data().dtc()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "-1", "101", "\"42\"", "null" })
  void invalidBatteryIsNotCoerced(String battery) throws Exception {
    var result = normalizer.normalize(
      event(
        "aster",
        "1",
        "{\"speed_kmh\":20,\"battery_pct\":" +
          battery +
          ",\"gps\":{\"lat\":18,\"lon\":73}}"
      ),
      mapping("aster", "1")
    );
    assertThat(result.valid()).isFalse();
  }

  @Test
  void rejectsInvalidCoordinatesAndDtcTypes() throws Exception {
    var result = normalizer.normalize(
      event(
        "aster",
        "1",
        "{\"speed_kmh\":999,\"battery_pct\":40,\"gps\":{\"lat\":100,\"lon\":200},\"dtc\":[\"invalid\"]}"
      ),
      mapping("aster", "1")
    );
    assertThat(result.issues()).hasSize(4);
  }

  @Test
  void rejectsObjectDtcAndUnsafeMapping() throws Exception {
    var e = event(
      "aster",
      "1",
      "{\"speed_kmh\":20,\"battery_pct\":40,\"gps\":{\"lat\":18,\"lon\":73},\"dtc\":{\"code\":\"P0301\"}}"
    );
    assertThat(
      normalizer.normalize(e, mapping("aster", "1")).valid()
    ).isFalse();
    var invalid = new Mapping(
      "m",
      "aster",
      "1",
      1,
      "DRAFT",
      List.of(new Rule("execute", "evil", 1, 0, true))
    );
    assertThat(normalizer.normalize(e, invalid).valid()).isFalse();
  }

  @Test
  void staleEventsRemainExplicitlyStale() throws Exception {
    var e = event(
      "aster",
      "1",
      "{\"speed_kmh\":20,\"battery_pct\":40,\"gps\":{\"lat\":18,\"lon\":73}}"
    );
    e = new Event(
      e.eventId(),
      e.vin(),
      e.oem(),
      e.schemaVersion(),
      Instant.now().minusSeconds(1000),
      e.sequence(),
      e.payload()
    );
    assertThat(
      normalizer.normalize(e, mapping("aster", "1")).data().quality()
    ).isEqualTo("STALE");
  }

  @Test
  void malformedEnvelopeRejected() throws Exception {
    var e = event("aster", "1", "[]");
    e = new Event(
      e.eventId(),
      "BADVIN",
      e.oem(),
      e.schemaVersion(),
      Instant.now().plusSeconds(300),
      0,
      e.payload()
    );
    assertThat(normalizer.normalize(e, mapping("aster", "1")).issues()).hasSize(
      3
    );
  }

  @Test
  void simulatorVinSpaceContains100000DistinctIdentifiers() {
    var seen = new HashSet<String>();
    for (int i = 1; i <= 100000; i++) {
      String vin = Simulator.vin(i);
      assertThat(vin).matches("[A-HJ-NPR-Z0-9]{17}");
      seen.add(vin);
    }
    assertThat(seen).hasSize(100000);
  }
}
