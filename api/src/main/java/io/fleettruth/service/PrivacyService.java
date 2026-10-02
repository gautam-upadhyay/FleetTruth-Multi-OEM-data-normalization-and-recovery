package io.fleettruth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PrivacyService {

  private final FleetService fleet;
  private final TransactionTemplate tx;
  private final TelemetryRollups rollups;

  public PrivacyService(FleetService fleet, TransactionTemplate tx, TelemetryRollups rollups) {
    this.fleet = fleet;
    this.tx = tx;
    this.rollups = rollups;
  }

  public static String hash(String tenant, String vin) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          (tenant + ":" + vin).getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> erase(
    String tenant,
    String actor,
    String vin,
    String confirmation
  ) {
    if (!vin.equals(confirmation)) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Type the exact VIN to confirm erasure"
    );
    return tx.execute(status -> {
      // Projection and replay take this same vehicle lock before handling payloads.
      var locked = fleet.rows(
        "SELECT vin FROM vehicles WHERE tenant_id=? AND vin=? FOR UPDATE",
        tenant,
        vin
      );
      if (locked.isEmpty()) throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "Vehicle not found"
      );
      String request = FleetService.id(),
        subject = hash(tenant, vin);
      String oem = (String) fleet.rows("SELECT oem_id FROM vehicle_catalog WHERE tenant_id=? AND vin=?", tenant, vin).getFirst().get("oemId");
      rollups.removeVehicle(tenant, vin);
      fleet.db.update(
        "INSERT INTO erasures VALUES(?,?,?,?,?,?)",
        request,
        tenant,
        subject,
        actor,
        "LOCAL_PURGED_EXTERNAL_PENDING",
        FleetService.time(Instant.now())
      );
      fleet.db.update(
        "DELETE FROM telemetry_revisions WHERE tenant_id=? AND event_id IN (SELECT id FROM raw_events WHERE tenant_id=? AND vin=?)",
        tenant,
        tenant,
        vin
      );
      fleet.db.update(
        "DELETE FROM projection_outbox WHERE tenant_id=? AND vin=?",
        tenant,
        vin
      );
      fleet.db.update(
        "DELETE FROM raw_events WHERE tenant_id=? AND vin=?",
        tenant,
        vin
      );
      fleet.db.update(
        "DELETE FROM alerts WHERE tenant_id=? AND vin=?",
        tenant,
        vin
      );
      rollups.afterErasure(tenant, oem);
      fleet.db.update(
        "DELETE FROM vehicle_state WHERE tenant_id=? AND vin=?",
        tenant,
        vin
      );
      fleet.db.update(
        "DELETE FROM vehicles WHERE tenant_id=? AND vin=?",
        tenant,
        vin
      );
      fleet.db.update(
        "UPDATE audit_log SET resource=?,detail='Subject evidence erased' WHERE tenant_id=? AND resource=?",
        subject,
        tenant,
        vin
      );
      fleet.audit(
        tenant,
        actor,
        "ERASURE_LOCAL_COMPLETED",
        request,
        "Local vehicle evidence removed. External stores, exports, broker retention and backups require purge verification."
      );
      return Map.of(
        "id",
        request,
        "status",
        "LOCAL_PURGED_EXTERNAL_PENDING",
        "subjectHash",
        subject,
        "externalVerificationRequired",
        true
      );
    });
  }
}
