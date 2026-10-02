package io.fleettruth.service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryRollups {
  private final JdbcTemplate db;
  private final boolean postgres;
  public TelemetryRollups(JdbcTemplate db) throws SQLException {
    this.db = db;
    try (var connection = db.getDataSource().getConnection()) {
      postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
    }
  }

  // These deltas share the ledger transaction: duplicates and rollbacks cannot inflate counts.
  public void record(String tenant, String oem, Instant received, Instant eventTime,
                     long total, long accepted, long quarantined) {
    update(tenant, oem, "TOTAL", Instant.EPOCH, total, accepted, quarantined, received);
    update(tenant, oem, "MINUTE", received.truncatedTo(ChronoUnit.MINUTES), total, accepted, quarantined, received);
    update(tenant, oem, "DAY", eventTime.truncatedTo(ChronoUnit.DAYS), total, accepted, quarantined, received);
  }

  private void update(String tenant, String oem, String kind, Instant bucket,
                      long total, long accepted, long quarantined, Instant received) {
    if (total == 0) {
      int updated = db.update("UPDATE telemetry_rollups SET accepted=accepted+?,quarantined=quarantined+? WHERE tenant_id=? AND oem_id=? AND kind=? AND bucket=?",
        accepted, quarantined, tenant, oem, kind, FleetService.time(bucket));
      if (updated != 1) throw new IllegalStateException("Missing telemetry summary for replay");
      return;
    }
    Object[] values = {tenant, oem, kind, FleetService.time(bucket), total, accepted, quarantined, FleetService.time(received)};
    if (postgres) {
      db.update("INSERT INTO telemetry_rollups VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,oem_id,kind,bucket) DO UPDATE SET total=telemetry_rollups.total+EXCLUDED.total,accepted=telemetry_rollups.accepted+EXCLUDED.accepted,quarantined=telemetry_rollups.quarantined+EXCLUDED.quarantined,last_seen=GREATEST(telemetry_rollups.last_seen,EXCLUDED.last_seen)", values);
    } else {
      db.update("MERGE INTO telemetry_rollups t USING (VALUES(CAST(? AS VARCHAR),CAST(? AS VARCHAR),CAST(? AS VARCHAR),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS BIGINT),CAST(? AS BIGINT),CAST(? AS BIGINT),CAST(? AS TIMESTAMP WITH TIME ZONE))) s(tenant_id,oem_id,kind,bucket,total,accepted,quarantined,last_seen) ON t.tenant_id=s.tenant_id AND t.oem_id=s.oem_id AND t.kind=s.kind AND t.bucket=s.bucket WHEN MATCHED THEN UPDATE SET total=t.total+s.total,accepted=t.accepted+s.accepted,quarantined=t.quarantined+s.quarantined,last_seen=GREATEST(t.last_seen,s.last_seen) WHEN NOT MATCHED THEN INSERT VALUES(s.tenant_id,s.oem_id,s.kind,s.bucket,s.total,s.accepted,s.quarantined,s.last_seen)", values);
    }
  }

  public void removeVehicle(String tenant, String vin) {
    String[] expressions = {"TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'", "DATE_TRUNC('minute',received_at)", "DATE_TRUNC('day',event_time AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'"};
    String[] kinds = {"TOTAL", "MINUTE", "DAY"};
    for (int i = 0; i < kinds.length; i++) {
      String kind = kinds[i], expression = expressions[i];
      db.query("SELECT oem_id," + expression + " AS bucket,COUNT(*),SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),SUM(CASE WHEN status='QUARANTINED' THEN 1 ELSE 0 END) FROM raw_events WHERE tenant_id=? AND vin=? GROUP BY oem_id," + expression + " ORDER BY oem_id,bucket",
        rs -> {
          db.update("UPDATE telemetry_rollups SET total=total-?,accepted=accepted-?,quarantined=quarantined-? WHERE tenant_id=? AND oem_id=? AND kind=? AND bucket=?",
            rs.getLong(3), rs.getLong(4), rs.getLong(5), tenant, rs.getString(1), kind, rs.getTimestamp(2));
        }, tenant, vin);
    }
  }

  public void afterErasure(String tenant, String oem) {
    db.update("DELETE FROM telemetry_rollups WHERE tenant_id=? AND oem_id=? AND total=0", tenant, oem);
    db.update("UPDATE telemetry_rollups SET last_seen=(SELECT MAX(received_at) FROM raw_events WHERE tenant_id=? AND oem_id=?) WHERE tenant_id=? AND oem_id=? AND kind='TOTAL'", tenant, oem, tenant, oem);
  }
}
