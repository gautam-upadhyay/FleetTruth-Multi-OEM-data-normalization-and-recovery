package io.fleettruth.service;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

  private final FleetService fleet;
  private final KnowledgeService knowledge;
  private final DriftModel model;

  public AssistantService(
    FleetService fleet,
    KnowledgeService knowledge,
    DriftModel model
  ) {
    this.fleet = fleet;
    this.knowledge = knowledge;
    this.model = model;
  }

  public Map<String, Object> answer(
    String tenant,
    String actor,
    String question
  ) {
    if (
      question == null || question.isBlank() || question.length() > 2000
    ) throw new IllegalArgumentException(
      "Question must contain 1-2000 characters"
    );
    String q = question.toLowerCase(Locale.ROOT);
    var tools = new ArrayList<Map<String, Object>>();
    String answer;
    String action = "";
    String target = "";
    if (
      q.matches(
        "(?s).*(approve|delete|erase|publish|start replay|execute|ignore.*instruction|other tenant).*"
      )
    ) {
      answer =
        "I can inspect fleet evidence and prepare recommendations. Publishing mappings, starting replay, and deleting data require your explicit action in the corresponding workspace. Your current request has not changed any data.";
    } else if (q.matches("(?s).*(mapping|schema|helix|drift|format).*")) {
      var notes = knowledge.search(question);
      tools.add(
        tool(
          "retrieve_runbook",
          notes.getFirst().get("title") + "; " + knowledge.storage()
        )
      );
      var scores = model.inspect(fleet, tenant);
      tools.add(
        tool(
          "score_drift_windows",
          scores.get("model") + "; advisory scores from persisted telemetry"
        )
      );
      var incidents = fleet.rows(
        "SELECT * FROM incidents WHERE tenant_id=? AND status='OPEN' ORDER BY created_at DESC",
        tenant
      );
      tools.add(
        tool("inspect_schema_incidents", incidents.size() + " open incidents")
      );
      var mappings = fleet.mappings(tenant);
      var draft = mappings
        .stream()
        .filter(m -> m.get("status").equals("DRAFT"))
        .findFirst();
      if (draft.isPresent()) {
        var m = draft.get();
        var preview = fleet.preview(tenant, (String) m.get("id"), null);
        tools.add(
          tool(
            "dry_run_mapping",
            preview.get("valid") +
              " / " +
              preview.get("total") +
              " samples passed"
          )
        );
        target = (String) m.get("id");
        action = "mapping";
        answer =
          "Helix is sending a new schema (version " +
          m.get("schemaVersion") +
          "). Its battery signal uses a fractional value: 0.42 means 42%. The proposed mapping reads /soc_fraction and multiplies by 100.\n\nThe stored sample validation passed " +
          preview.get("valid") +
          " of " +
          preview.get("total") +
          " events. Until you approve this version, affected events remain quarantined and their vehicle state is marked untrusted.\n\nReview the source paths and units in Mapping Studio, approve the validated mapping, then replay the affected events. Every step will be recorded in the audit trail.";
      } else answer =
        "There are " +
        incidents.size() +
        " open schema incidents and no draft mappings awaiting review. Approved mapping versions are currently available. Check OEM health for data-quality issues that may require new sample evidence.";
    } else if (q.matches("(?s).*(replay|recover|quarantin).*")) {
      var oems = fleet.oems(tenant);
      tools.add(
        tool("inspect_quarantine", oems.size() + " OEM streams inspected")
      );
      long total = oems
        .stream()
        .mapToLong(o -> ((Number) o.get("quarantined")).longValue())
        .sum();
      long recovered = fleet.count(
        "SELECT COALESCE(SUM(recovered),0) FROM replay_jobs WHERE tenant_id=?",
        tenant
      );
      answer =
        "There are " +
        total +
        " quarantined events in this workspace. Completed replay work has recovered " +
        recovered +
        " events.\n\nApprove the corresponding schema mapping before replay. Recovery uses the original event time and preserves normalization revisions; older events cannot replace a newer vehicle state. Events that still fail validation remain quarantined.";
      action = "recovery";
    } else {
      var overview = fleet.overview(tenant);
      tools.add(
        tool(
          "get_fleet_health",
          overview.get("vehicles") + " enrolled vehicles"
        )
      );
      tools.add(
        tool(
          "get_priority_alerts",
          overview.get("criticalAlerts") + " critical alerts"
        )
      );
      answer =
        "Your fleet has " +
        overview.get("vehicles") +
        " enrolled synthetic vehicles, with " +
        overview.get("reporting") +
        " reporting in the last five minutes.\n\nThere are " +
        overview.get("criticalAlerts") +
        " critical vehicle alerts and " +
        overview.get("openIncidents") +
        " open integration incidents. " +
        overview.get("quarantined") +
        " events are quarantined; the accepted-event rate is " +
        overview.get("quality") +
        "%.\n\nPrioritize critical vehicle alerts, then resolve the integration incident so affected vehicle signals become trustworthy again. These figures are from the current workspace, not a projected production benchmark.";
      action = "alerts";
    }
    for (var tool : tools)
      fleet.audit(
        tenant,
        actor,
        "AGENT_TOOL",
        (String) tool.get("name"),
        (String) tool.get("result")
      );
    fleet.audit(
      tenant,
      actor,
      "AGENT_RESPONSE",
      "assistant",
      "Read-only operational recommendation; " + tools.size() + " tools used"
    );
    return Map.of(
      "answer",
      answer,
      "tools",
      tools,
      "action",
      action,
      "target",
      target,
      "mode",
      "GROUNDED_TOOL_ROUTER",
      "timestamp",
      java.time.Instant.now()
    );
  }

  private Map<String, Object> tool(String name, String result) {
    return Map.of("name", name, "result", result, "status", "completed");
  }
}
