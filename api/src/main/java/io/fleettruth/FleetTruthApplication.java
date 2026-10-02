package io.fleettruth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  exclude = org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration.class
)
@EnableScheduling
public class FleetTruthApplication {

  public static void main(String[] args) throws Exception {
    if (java.util.Arrays.asList(args).contains("--load-generator")) {
      io.fleettruth.service.LoadGenerator.run();
      return;
    }
    SpringApplication.run(FleetTruthApplication.class, args);
  }
}
