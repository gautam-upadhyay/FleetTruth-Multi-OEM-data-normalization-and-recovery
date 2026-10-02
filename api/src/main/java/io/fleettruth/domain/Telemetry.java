package io.fleettruth.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Telemetry {

  private Telemetry() {}

  public record Event(
    @NotBlank @Size(max = 100) String eventId,
    @Pattern(regexp = "[A-HJ-NPR-Z0-9]{17}") String vin,
    @NotBlank @Size(max = 32) String oem,
    @NotBlank @Size(max = 24) String schemaVersion,
    @NotNull Instant eventTime,
    @PositiveOrZero long sequence,
    @NotNull JsonNode payload
  ) {}

  public record Rule(
    String field,
    String source,
    double scale,
    double offset,
    boolean required
  ) {}

  public record Mapping(
    String id,
    String oem,
    String schemaVersion,
    int version,
    String status,
    List<Rule> rules
  ) {}

  public record Normalized(
    Double speedKmh,
    Double socPct,
    Double odometerKm,
    Double latitude,
    Double longitude,
    List<String> dtc,
    String quality,
    String mappingId,
    Instant eventTime
  ) {}

  public record Result(boolean valid, Normalized data, List<String> issues) {}

  public record Intake(String eventId, String status, String reason) {}
}
