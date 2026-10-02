CREATE INDEX ix_event_oem_recent ON raw_events(tenant_id,oem_id,received_at DESC);
