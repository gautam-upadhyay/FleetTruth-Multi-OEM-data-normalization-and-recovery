package io.fleettruth;

import au.com.dius.pact.provider.junit5.*;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import io.fleettruth.api.AuthController;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@Provider("FleetTruthAPI")
@PactFolder("target/pacts")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"fleettruth.vehicles=8","fleettruth.simulation=false","spring.datasource.url=jdbc:h2:mem:pact;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class WorkspaceContractIT {
  @LocalServerPort int port;
  @Autowired AuthController auth;
  @BeforeEach void target(PactVerificationContext context) { context.setTarget(new HttpTestTarget("127.0.0.1",port)); }
  @TestTemplate @ExtendWith(PactVerificationInvocationContextProvider.class)
  void verify(PactVerificationContext context,HttpRequest request) {
    if(request.containsHeader("Authorization")) request.setHeader("Authorization","Bearer "+auth.login(new AuthController.Login("engineer@fleettruth.demo","FleetTruth2026!")).get("token"));
    context.verifyInteraction();
  }
}
