package io.fleettruth;

import static org.assertj.core.api.Assertions.*;
import io.fleettruth.service.ProjectionCircuits;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectionCircuitsTest {
  @Test void opensRejectsAndRecoversWithoutCallingTheFailedStore() {
    var circuits = new ProjectionCircuits(new SimpleMeterRegistry());
    var called = new AtomicInteger();
    for (int i = 0; i < 5; i++) assertThatThrownBy(() -> circuits.execute("redis", () -> {
      called.incrementAndGet(); throw new IllegalStateException("Unavailable");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(circuits.circuit("redis").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() -> circuits.execute("redis", called::incrementAndGet)).isInstanceOf(CallNotPermittedException.class);
    assertThat(called).hasValue(5);
    assertThat(circuits.circuit("cassandra").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    circuits.circuit("redis").transitionToHalfOpenState();
    circuits.execute("redis", called::incrementAndGet);
    circuits.execute("redis", called::incrementAndGet);
    assertThat(circuits.circuit("redis").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThatThrownBy(() -> circuits.circuit("unknown")).isInstanceOf(IllegalArgumentException.class);
  }
}
