ALTER TABLE vehicle_state ADD COLUMN sequence_no BIGINT NOT NULL DEFAULT 0;
CREATE TABLE projection_outbox (id VARCHAR(180) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, vin VARCHAR(17) NOT NULL, payload TEXT NOT NULL, status VARCHAR(16) NOT NULL, attempts INTEGER NOT NULL, available_at TIMESTAMP WITH TIME ZONE NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX ix_projection_pending ON projection_outbox(status,available_at);
CREATE INDEX ix_vehicle_model ON vehicles(tenant_id,model_id,vin);
