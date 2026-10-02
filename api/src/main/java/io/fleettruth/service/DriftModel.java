package io.fleettruth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Advisory classifier. Contract validation and human approval remain authoritative. */
@Service
public class DriftModel {

  public record Parameters(
    List<String> features,
    double[] mean,
    double[] scale,
    double[] coefficients,
    double intercept,
    double threshold,
    int trainingSeed,
    int evaluationSeed
  ) {}

  private final Parameters parameters;

  public DriftModel(ObjectMapper json) throws IOException {
    try (
      var input = new ClassPathResource("drift-model.json").getInputStream()
    ) {
      parameters = json.readValue(input, Parameters.class);
    }
  }

  public double probability(double[] features) {
    if (
      features.length != parameters.features().size()
    ) throw new IllegalArgumentException("Expected five drift features");
    double value = parameters.intercept();
    for (int i = 0; i < features.length; i++) {
      if (
        !Double.isFinite(features[i]) || features[i] < 0
      ) throw new IllegalArgumentException(
        "Features must be finite and nonnegative"
      );
      value +=
        ((features[i] - parameters.mean()[i]) / parameters.scale()[i]) *
        parameters.coefficients()[i];
    }
    return 1 / (1 + Math.exp(-Math.max(-700, Math.min(700, value))));
  }

  public Map<String, Object> inspect(FleetService fleet, String tenant) {
    var results = new ArrayList<Map<String, Object>>();
    for (String oem : Simulator.OEMS) {
      var rows = fleet.rows(
        "SELECT * FROM raw_events WHERE tenant_id=? AND oem_id=? ORDER BY received_at DESC LIMIT 200",
        tenant,
        oem
      );
      double missing = 0,
        type = 0,
        range = 0,
        unknown = 0;
      int recent = Math.min(100, rows.size());
      var means = new double[2];
      var sizes = new int[2];
      for (int i = 0; i < rows.size(); i++) {
        var row = rows.get(i);
        String reason = Objects.toString(row.get("reason"), "").toLowerCase(
          Locale.ROOT
        );
        if (i < recent) {
          if (reason.contains("missing")) missing++;
          if (reason.contains("number") || reason.contains("type")) type++;
          if (reason.contains("range")) range++;
          if (
            fleet.activeMapping(
              tenant,
              oem,
              (String) row.get("schemaVersion")
            ) == null
          ) unknown++;
        }
        if (row.get("normalized") instanceof String normalized) {
          var n = fleet.decode(
            normalized,
            io.fleettruth.domain.Telemetry.Normalized.class
          );
          if (n.socPct() != null) {
            int bucket = i < 100 ? 0 : 1;
            means[bucket] += n.socPct();
            sizes[bucket]++;
          }
        }
      }
      double shift =
        sizes[0] > 0 && sizes[1] > 0
          ? Math.abs(means[0] / sizes[0] - means[1] / sizes[1]) /
            Math.max(1, means[1] / sizes[1])
          : 0;
      double[] features =
        recent == 0
          ? new double[5]
          : new double[] {
              missing / recent,
              type / recent,
              range / recent,
              unknown > 0 ? 1 : 0,
              shift,
            };
      var named = new LinkedHashMap<String, Double>();
      for (int i = 0; i < features.length; i++) named.put(
        parameters.features().get(i),
        features[i]
      );
      double probability = probability(features);
      results.add(
        Map.of(
          "oem",
          oem,
          "samples",
          recent,
          "features",
          named,
          "probability",
          probability,
          "status",
          recent == 0
            ? "NO_DATA"
            : probability >= parameters.threshold()
              ? "REVIEW"
              : "STABLE"
        )
      );
    }
    return Map.of(
      "model",
      "standardized-logistic-regression-v1",
      "advisory",
      true,
      "evaluation",
      "Synthetic holdout; not real-OEM validation",
      "streams",
      results
    );
  }
}
