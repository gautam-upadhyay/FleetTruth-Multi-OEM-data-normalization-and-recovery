package io.fleettruth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fleettruth.domain.VinValidator;
import io.fleettruth.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IntelligenceTest {

  @Test
  void javaInferenceMatchesPythonReference() throws Exception {
    var model = new DriftModel(new ObjectMapper());
    assertThat(model.probability(new double[] { 0, 0, 0, 0, 0 })).isCloseTo(
      1.6807638001472643e-5,
      within(1e-12)
    );
    assertThat(model.probability(new double[] { 0, 0, 0, 1, 0 })).isCloseTo(
      .9612319671353949,
      within(1e-12)
    );
    assertThat(
      model.probability(new double[] { .8, 0, 0, 0, 0 })
    ).isGreaterThan(.9999);
    assertThatThrownBy(() ->
      model.probability(new double[] { 1 })
    ).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
      model.probability(new double[] { Double.NaN, 0, 0, 0, 0 })
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lexicalRunbookRetrievalIsDeterministicAndBounded() {
    var knowledge = new KnowledgeService(new JdbcTemplate(), false);
    var found = knowledge.search("Helix soc_fraction battery mapping schema");
    assertThat(found).hasSize(2);
    assertThat(found.getFirst().get("id")).isEqualTo("helix-units");
    assertThat(knowledge.storage()).isEqualTo("LOCAL_EXACT_COSINE");
    assertThat(KnowledgeService.embed("!!!")).containsOnly(0);
    assertThatThrownBy(() -> knowledge.search(" ")).isInstanceOf(
      IllegalArgumentException.class
    );
  }

  @Test
  void vinCheckDigitRejectsCorruption() {
    for (int i = 1; i <= 100000; i++) assertThat(
      VinValidator.valid(Simulator.vin(i))
    ).isTrue();
    assertThat(VinValidator.valid(null)).isFalse();
    assertThat(VinValidator.valid("123")).isFalse();
    String vin = Simulator.vin(25);
    String bad =
      vin.substring(0, 8) +
      (vin.charAt(8) == '0' ? '1' : '0') +
      vin.substring(9);
    assertThat(VinValidator.valid(bad)).isFalse();
  }

  @Test
  void privacyHashesAreTenantScoped() {
    assertThat(PrivacyService.hash("a", "VIN"))
      .hasSize(64)
      .isEqualTo(PrivacyService.hash("a", "VIN"))
      .isNotEqualTo(PrivacyService.hash("b", "VIN"));
  }

  @Test
  void vinTransliterationCoversEveryPermittedLetter() {
    String letters = "ABCDEFGHJKLMNPRSTUVWXYZ";
    int[] values = {
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      1,
      2,
      3,
      4,
      5,
      7,
      9,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      9,
    };
    for (int i = 0; i < letters.length(); i++) {
      char[] vin = "00000000000000000".toCharArray();
      vin[0] = letters.charAt(i);
      int check = (values[i] * 8) % 11;
      vin[8] = check == 10 ? 'X' : (char) ('0' + check);
      assertThat(VinValidator.valid(new String(vin)))
        .as("letter %s", letters.charAt(i))
        .isTrue();
    }
    for (char forbidden : new char[] { 'I', 'O', 'Q', 'a', '-' }) {
      assertThat(VinValidator.valid(forbidden + "0000000000000000")).isFalse();
    }
  }
}
