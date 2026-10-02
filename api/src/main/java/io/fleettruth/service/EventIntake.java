package io.fleettruth.service;

import io.fleettruth.domain.Telemetry.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventIntake {

  public record Envelope(String tenant, Event event) {}

  private final boolean kafka;
  private final KafkaTemplate<String, String> producer;
  private final FleetService fleet;

  public EventIntake(
    @Value("${fleettruth.kafka}") boolean kafka,
    KafkaTemplate<String, String> producer,
    FleetService fleet
  ) {
    this.kafka = kafka;
    this.producer = producer;
    this.fleet = fleet;
  }

  public Intake accept(String tenant, Event event) {
    if (!kafka) return fleet.ingest(tenant, event);
    if (
      fleet.count(
        "SELECT COUNT(*) FROM vehicle_catalog WHERE tenant_id=? AND vin=? AND oem_id=?",
        tenant,
        event.vin(),
        event.oem()
      ) == 0
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Vehicle does not belong to this tenant and OEM"
    );
    try {
      producer
        .send(
          "fleettruth.raw",
          tenant + ":" + event.vin(),
          fleet.encode(new Envelope(tenant, event))
        )
        .get(5, TimeUnit.SECONDS);
      return new Intake(
        event.eventId(),
        "QUEUED",
        "Durably acknowledged by Kafka"
      );
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Intake interrupted. Retry using the same event ID."
      );
    } catch (Exception ex) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Intake unavailable. Retry using the same event ID."
      );
    }
  }
}
