CREATE INDEX ix_event_status ON raw_events(tenant_id,status);
CREATE INDEX ix_event_replay_cursor ON raw_events(tenant_id,oem_id,status,id);
