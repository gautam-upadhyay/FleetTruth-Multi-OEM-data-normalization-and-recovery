package io.fleettruth.service;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Local lexical feature vectors; no external embedding API or private telemetry. */
@Service
public class KnowledgeService {

  private record Note(String id, String title, String text) {}

  private static final List<Note> NOTES = List.of(
    new Note(
      "helix-units",
      "Helix battery units",
      "Helix schema drift changes battery state_of_charge percent to soc_fraction fraction. Multiply fractional battery by 100. Velocity mph converts to km/h by 1.609344. Require samples and human mapping approval before replay."
    ),
    new Note(
      "replay",
      "Recovery safeguards",
      "Quarantined events retain original timestamps and versioned normalization evidence. Replay requires approved schema mapping. Deduplicate tenant event ID. Old out-of-order events cannot replace newer vehicle state."
    ),
    new Note(
      "battery",
      "Battery alert policy",
      "Moving vehicles with battery below 15 percent need route and charging review. At 8 percent or below create a critical low battery alert. Review signal quality before dispatch."
    ),
    new Note(
      "privacy",
      "Privacy and access",
      "Tenant isolation and role based access apply to every query. Viewer coordinates are approximate. Admin erasure removes local vehicle evidence; Kafka archives and backups require separate verified purge."
    )
  );
  private final JdbcTemplate db;
  private final boolean vector;

  public KnowledgeService(
    JdbcTemplate db,
    @Value("${fleettruth.vector:false}") boolean vector
  ) {
    this.db = db;
    this.vector = vector;
    if (vector) {
      db.execute("CREATE EXTENSION IF NOT EXISTS vector");
      db.execute(
        "CREATE TABLE IF NOT EXISTS runbook_vectors (id varchar(64) PRIMARY KEY,title text NOT NULL,body text NOT NULL,embedding vector(128) NOT NULL)"
      );
      for (var note : NOTES)
        db.update(
          "INSERT INTO runbook_vectors VALUES(?,?,?,CAST(? AS vector)) ON CONFLICT(id) DO UPDATE SET title=EXCLUDED.title,body=EXCLUDED.body,embedding=EXCLUDED.embedding",
          note.id(),
          note.title(),
          note.text(),
          literal(embed(note.title() + " " + note.text()))
        );
    }
  }

  public List<Map<String, Object>> search(String query) {
    if (
      query == null || query.isBlank() || query.length() > 2000
    ) throw new IllegalArgumentException(
      "Query must contain 1-2000 characters"
    );
    double[] vectorQuery = embed(query);
    if (vector) return db.queryForList(
      "SELECT id,title,body,1-(embedding <=> CAST(? AS vector)) AS score FROM runbook_vectors ORDER BY embedding <=> CAST(? AS vector) LIMIT 2",
      literal(vectorQuery),
      literal(vectorQuery)
    );
    return NOTES.stream()
      .map(n ->
        Map.<String, Object>of(
          "id",
          n.id(),
          "title",
          n.title(),
          "body",
          n.text(),
          "score",
          dot(vectorQuery, embed(n.title() + " " + n.text()))
        )
      )
      .sorted(
        Comparator.comparingDouble((Map<String, Object> n) ->
          ((Number) n.get("score")).doubleValue()
        ).reversed()
      )
      .limit(2)
      .toList();
  }

  public String storage() {
    return vector ? "PGVECTOR_EXACT_COSINE" : "LOCAL_EXACT_COSINE";
  }

  public static double[] embed(String text) {
    double[] values = new double[128];
    for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
      if (token.length() > 2) values[
        Math.floorMod(token.hashCode(), values.length)
      ]++;
    }
    double norm = Math.sqrt(dot(values, values));
    if (norm > 0) for (int i = 0; i < values.length; i++) values[i] /= norm;
    return values;
  }

  private static double dot(double[] a, double[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
    return sum;
  }

  private static String literal(double[] value) {
    return Arrays.toString(value);
  }
}
