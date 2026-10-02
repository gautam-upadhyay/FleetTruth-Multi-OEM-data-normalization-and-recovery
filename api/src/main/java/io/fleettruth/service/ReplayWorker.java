package io.fleettruth.service;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReplayWorker {

  private final FleetService fleet;
  private final TransactionTemplate tx;

  public ReplayWorker(FleetService fleet, TransactionTemplate tx) {
    this.fleet = fleet;
    this.tx = tx;
  }

  @Scheduled(fixedDelay = 500, initialDelay = 2000)
  public void advance() {
    // The job checkpoint and its page commit together; a process crash rolls both back.
    for (int i = 0; i < 4; i++) {
      Boolean worked = tx.execute(transaction -> {
        var jobs = fleet.rows(
          "SELECT * FROM replay_jobs WHERE status='RUNNING' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED"
        );
        if (jobs.isEmpty()) return false;
        var job = jobs.getFirst();
        String id = (String) job.get("id"),
          tenant = (String) job.get("tenantId"),
          oem = (String) job.get("oemId"),
          actor = (String) job.get("requestedBy"),
          cursor = (String) job.get("cursorId");
        var page = fleet.rows(
          "SELECT * FROM raw_events WHERE tenant_id=? AND oem_id=? AND status='QUARANTINED' AND received_at<=? AND id>? ORDER BY id LIMIT 100",
          tenant,
          oem,
          FleetService.time(Instant.parse((String) job.get("createdAt"))),
          cursor
        );
        int recovered = ((Number) job.get("recovered")).intValue(),
          failed = ((Number) job.get("failed")).intValue();
        for (var sample : page) {
          var event = fleet.event(sample);
          var result = fleet.replayEvent(tenant, event);
          if (result.status().equals("RECOVERED")) recovered++;
          else failed++;
          cursor = event.eventId();
        }
        fleet.db.update(
          "UPDATE replay_jobs SET recovered=?,failed=?,cursor_id=? WHERE tenant_id=? AND id=?",
          recovered,
          failed,
          cursor,
          tenant,
          id
        );
        if (page.size() < 100) {
          int skipped = Math.max(
            0,
            ((Number) job.get("total")).intValue() - recovered - failed
          );
          fleet.db.update(
            "UPDATE replay_jobs SET status=?,skipped=?,completed_at=? WHERE tenant_id=? AND id=?",
            failed == 0 ? "COMPLETED" : "PARTIAL",
            skipped,
            FleetService.time(Instant.now()),
            tenant,
            id
          );
          fleet.db.update(
            "UPDATE incidents SET status='RESOLVED',resolved_at=? WHERE tenant_id=? AND oem_id=? AND NOT EXISTS(SELECT 1 FROM raw_events r WHERE r.tenant_id=incidents.tenant_id AND r.oem_id=incidents.oem_id AND r.schema_version=incidents.schema_version AND r.status='QUARANTINED')",
            FleetService.time(Instant.now()),
            tenant,
            oem
          );
          fleet.audit(
            tenant,
            actor,
            "REPLAY_COMPLETED",
            id,
            recovered +
              " recovered; " +
              failed +
              " failed validation; " +
              skipped +
              " no longer eligible"
          );
        }
        return true;
      });
      if (!Boolean.TRUE.equals(worked)) return;
    }
  }
}
