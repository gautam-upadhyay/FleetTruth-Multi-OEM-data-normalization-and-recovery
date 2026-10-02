package io.fleettruth;

import org.junit.platform.suite.api.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key="cucumber.glue", value="io.fleettruth.bdd")
@ConfigurationParameter(key="cucumber.plugin", value="pretty,json:target/cucumber.json,html:target/cucumber.html")
@ConfigurationParameter(key="cucumber.publish.quiet", value="true")
public class AcceptanceTest {}
