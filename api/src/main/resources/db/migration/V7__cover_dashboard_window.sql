DROP INDEX ix_event_recent;
CREATE INDEX ix_event_recent ON raw_events(tenant_id,received_at DESC,status,id);
