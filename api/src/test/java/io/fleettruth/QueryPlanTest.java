package io.fleettruth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.service.Simulator;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QueryPlanTest {

  @Test
  void collectRealH2PlansForCatalogPagination() throws Exception {
    var source = new DriverManagerDataSource(
      "jdbc:h2:mem:query_plans;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "sa",
      ""
    );
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    db.update(
      "INSERT INTO tenants VALUES('plan-tenant','Synthetic plan workload')"
    );
    db.update(
      "INSERT INTO fleets VALUES('plan-fleet','plan-tenant','Test fleet','Test region')"
    );
    db.update(
      "INSERT INTO drivers VALUES('plan-driver','plan-tenant','Synthetic driver')"
    );
    db.update("INSERT INTO oems VALUES('aster','Aster Motors','#24876a')");
    db.update(
      "INSERT INTO vehicle_models VALUES('plan-model','aster','Synthetic model','EV')"
    );
    for (int offset = 0; offset < 100000; offset += 1000) {
      var batch = new ArrayList<Object[]>();
      for (int i = offset; i < offset + 1000; i++) batch.add(new Object[] {
        Simulator.vin(i + 1),
        "plan-tenant",
        "plan-fleet",
        "TEST " + i,
        "plan-model",
        "plan-driver",
      });
      db.batchUpdate("INSERT INTO vehicles VALUES(?,?,?,?,?,?)", batch);
    }
    db.execute("ANALYZE");
    String before =
      "SELECT v.vin FROM vehicle_catalog v JOIN oems o ON o.id=v.oem_id JOIN fleets f ON f.id=v.fleet_id LEFT JOIN vehicle_state s ON s.vin=v.vin WHERE v.tenant_id='plan-tenant' AND (UPPER(v.vin) LIKE '%%' OR UPPER(v.registration) LIKE '%%' OR UPPER(v.driver_name) LIKE '%%') AND (''='' OR v.oem_id='') AND v.vin>'' ORDER BY v.vin LIMIT 26";
    String after =
      "SELECT v.vin FROM (SELECT * FROM vehicles v WHERE v.tenant_id='plan-tenant' AND v.vin>'' ORDER BY v.tenant_id,v.vin LIMIT 26) v JOIN vehicle_models m ON m.id=v.model_id JOIN oems o ON o.id=m.oem_id JOIN fleets f ON f.id=v.fleet_id LEFT JOIN drivers d ON d.id=v.driver_id LEFT JOIN vehicle_state s ON s.vin=v.vin AND s.tenant_id=v.tenant_id ORDER BY v.vin";
    var expected = db.queryForList(before, String.class);
    assertThat(db.queryForList(after, String.class))
      .containsExactlyElementsOf(expected)
      .hasSize(26);
    long started = System.nanoTime();
    String oldPlan = db.queryForObject(
      "EXPLAIN ANALYZE " + before,
      String.class
    );
    double oldMs = (System.nanoTime() - started) / 1e6;
    started = System.nanoTime();
    String newPlan = db.queryForObject(
      "EXPLAIN ANALYZE " + after,
      String.class
    );
    double newMs = (System.nanoTime() - started) / 1e6;
    var report = Map.of(
      "database",
      "H2 2.3, PostgreSQL compatibility mode",
      "rows",
      100000,
      "before",
      Map.of("sql", before, "plan", oldPlan, "elapsedMs", oldMs),
      "after",
      Map.of("sql", after, "plan", newPlan, "elapsedMs", newMs),
      "scope",
      "Actual catalog pagination shapes, synthetic single-tenant microbenchmark; PostgreSQL production plans remain unverified"
    );
    Path output = Path.of(System.getProperty("basedir"))
      .getParent()
      .resolve("evidence/sql-h2-query-plans.json");
    Files.createDirectories(output.getParent());
    new ObjectMapper()
      .writerWithDefaultPrettyPrinter()
      .writeValue(output.toFile(), report);
  }
}
