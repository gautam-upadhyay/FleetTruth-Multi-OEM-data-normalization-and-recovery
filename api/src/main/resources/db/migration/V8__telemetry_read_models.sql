CREATE INDEX ix_event_mapping_sample ON raw_events(tenant_id,oem_id,schema_version,received_at DESC);

CREATE TABLE telemetry_rollups (
  tenant_id VARCHAR(64) NOT NULL,
  oem_id VARCHAR(32) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  bucket TIMESTAMP WITH TIME ZONE NOT NULL,
  total BIGINT NOT NULL,
  accepted BIGINT NOT NULL,
  quarantined BIGINT NOT NULL,
  last_seen TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY(tenant_id,oem_id,kind,bucket),
  CHECK(total >= 0 AND accepted >= 0 AND quarantined >= 0)
);
CREATE INDEX ix_rollup_window ON telemetry_rollups(tenant_id,kind,bucket);

INSERT INTO telemetry_rollups
SELECT tenant_id,oem_id,'TOTAL',TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00',COUNT(*),
  SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),
  SUM(CASE WHEN status='QUARANTINED' THEN 1 ELSE 0 END),MAX(received_at)
FROM raw_events GROUP BY tenant_id,oem_id;
INSERT INTO telemetry_rollups
SELECT tenant_id,oem_id,'MINUTE',DATE_TRUNC('minute',received_at),COUNT(*),
  SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),
  SUM(CASE WHEN status='QUARANTINED' THEN 1 ELSE 0 END),MAX(received_at)
FROM raw_events GROUP BY tenant_id,oem_id,DATE_TRUNC('minute',received_at);
INSERT INTO telemetry_rollups
SELECT tenant_id,oem_id,'DAY',DATE_TRUNC('day',event_time AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',COUNT(*),
  SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),
  SUM(CASE WHEN status='QUARANTINED' THEN 1 ELSE 0 END),MAX(received_at)
FROM raw_events GROUP BY tenant_id,oem_id,DATE_TRUNC('day',event_time AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
