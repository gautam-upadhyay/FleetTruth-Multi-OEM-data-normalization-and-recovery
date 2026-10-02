Feature: Trustworthy multi-OEM normalization
  Scenario: An OEM changes battery units
    Given Helix schema 1 reports battery percentage
    When schema 2 sends soc_fraction equal to 0.07
    Then the event is quarantined without an approved schema 2 mapping
    And its vehicle state is marked untrusted
    When an engineer validates the fraction-to-percentage mapping
    And explicitly approves the mapping
    And requests replay
    Then battery percentage becomes 7
    And a moving vehicle generates a critical low-battery alert
    And the original event, mapping revision and approval are auditable

  Scenario: A reviewer cannot publish a mapping
    Given a viewer access token
    When the viewer requests mapping approval
    Then the API returns 403

  Scenario: Local privacy erasure prevents replay resurrection
    Given an administrator with the matching tenant claim
    When the administrator confirms a vehicle VIN for erasure
    Then its local telemetry, alerts, state and outbox are removed
    And subsequent ingest for the removed vehicle is rejected
    And external erasure remains pending until independently verified
