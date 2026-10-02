package io.fleettruth;

import static org.assertj.core.api.Assertions.*;
import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.*;
import au.com.dius.pact.consumer.junit5.*;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

@PactConsumerTest
@PactTestFor(pactVersion=au.com.dius.pact.core.model.PactSpecVersion.V3)
public class WorkspaceContractTest {
  @Pact(consumer="FleetTruthWorkspace",provider="FleetTruthAPI")
  public RequestResponsePact identity(PactDslWithProvider builder) {
    return builder.uponReceiving("the workspace loads its signed-in profile").path("/api/auth/me").method("GET")
      .headers("Authorization","Bearer contract-placeholder").willRespondWith().status(200)
      .headers(Map.of("Content-Type","application/json"))
      .body(new PactDslJsonBody().stringValue("email","engineer@fleettruth.demo").stringValue("tenantId","tenant-demo").array("roles").stringValue("ENGINEER").closeArray()).toPact();
  }
  @Test @PactTestFor(pactMethod="identity")
  void parsesTheProfileContract(MockServer server) throws Exception {
    var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.getUrl()+"/api/auth/me")).header("Authorization","Bearer contract-placeholder").GET().build(),HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var account=new ObjectMapper().readTree(response.body());
    assertThat(account.get("tenantId").asText()).isEqualTo("tenant-demo");
    assertThat(account.get("roles").get(0).asText()).isEqualTo("ENGINEER");
  }
  @Pact(consumer="FleetTruthWorkspace",provider="FleetTruthAPI")
  public RequestResponsePact anonymous(PactDslWithProvider builder) {
    return builder.uponReceiving("an unauthenticated fleet overview request").path("/api/overview").method("GET").willRespondWith().status(401).toPact();
  }
  @Test @PactTestFor(pactMethod="anonymous")
  void rejectsMissingAuthentication(MockServer server) throws Exception {
    var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.getUrl()+"/api/overview")).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }
}
