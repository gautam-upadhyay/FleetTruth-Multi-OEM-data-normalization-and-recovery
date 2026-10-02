package io.fleettruth.config;

import io.fleettruth.service.*;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "fleettruth.kafka", havingValue = "true")
public class KafkaPipeline {

  private final FleetService fleet;
  private final KafkaTemplate<String, String> kafka;

  public KafkaPipeline(
    FleetService fleet,
    KafkaTemplate<String, String> kafka
  ) {
    this.fleet = fleet;
    this.kafka = kafka;
  }

  @Bean
  NewTopic raw(
    @Value("${fleettruth.kafka-partitions}") int parts,
    @Value("${fleettruth.kafka-replicas}") int replicas
  ) {
    return TopicBuilder.name("fleettruth.raw")
      .partitions(parts)
      .replicas(replicas)
      .config("retention.ms", "21600000")
      .build();
  }

  @Bean
  NewTopic normalized(
    @Value("${fleettruth.kafka-partitions}") int parts,
    @Value("${fleettruth.kafka-replicas}") int replicas
  ) {
    return TopicBuilder.name("fleettruth.normalized")
      .partitions(parts)
      .replicas(replicas)
      .build();
  }

  @Bean
  NewTopic deadLetter(
    @Value("${fleettruth.kafka-partitions}") int parts,
    @Value("${fleettruth.kafka-replicas}") int replicas
  ) {
    return TopicBuilder.name("fleettruth.raw.DLT")
      .partitions(parts)
      .replicas(replicas)
      .build();
  }

  @Bean
  DefaultErrorHandler errorHandler() {
    var handler = new DefaultErrorHandler(
      new DeadLetterPublishingRecoverer(kafka, (record, error) ->
        new TopicPartition("fleettruth.raw.DLT", record.partition())
      ),
      new FixedBackOff(1000, 3)
    );
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }

  @KafkaListener(
    topics = "fleettruth.raw",
    concurrency = "${KAFKA_CONCURRENCY:4}"
  )
  public void consume(String payload) throws Exception {
    EventIntake.Envelope envelope = fleet.decode(
      payload,
      EventIntake.Envelope.class
    );
    var intake = fleet.ingest(envelope.tenant(), envelope.event());
    if (
      intake.status().equals("ACCEPTED") || intake.status().equals("DUPLICATE")
    ) {
      var evidence = fleet.eventDetail(
        envelope.tenant(),
        envelope.event().eventId()
      );
      if (evidence.get("normalized") != null) kafka
        .send(
          "fleettruth.normalized",
          envelope.tenant() + ":" + envelope.event().vin(),
          fleet.encode(
            Map.of(
              "tenant",
              envelope.tenant(),
              "event",
              envelope.event(),
              "normalized",
              evidence.get("normalized")
            )
          )
        )
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
