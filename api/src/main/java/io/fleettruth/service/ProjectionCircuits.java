package io.fleettruth.service;

import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProjectionCircuits {
  private final Map<String, CircuitBreaker> circuits;
  public ProjectionCircuits(MeterRegistry metrics) {
    var config = CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5)
      .failureRateThreshold(50).slowCallDurationThreshold(Duration.ofSeconds(2))
      .slowCallRateThreshold(80).waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(2).build();
    circuits = Map.of("redis", CircuitBreaker.of("redis", config), "cassandra", CircuitBreaker.of("cassandra", config));
    circuits.forEach((name, circuit) -> {
      metrics.gauge("fleettruth_projection_circuit_state", java.util.List.of(io.micrometer.core.instrument.Tag.of("store", name)), circuit, c -> c.getState().getOrder());
      circuit.getEventPublisher().onStateTransition(event -> {
        metrics.counter("fleettruth_projection_circuit_transitions", "store", name).increment();
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Projection circuit {}: {}", name, event.getStateTransition());
      });
    });
  }
  public CircuitBreaker circuit(String store) {
    var circuit = circuits.get(store);
    if (circuit == null) throw new IllegalArgumentException("Unknown projection store");
    return circuit;
  }
  public void execute(String store, Runnable action) { circuit(store).executeRunnable(action); }
}
