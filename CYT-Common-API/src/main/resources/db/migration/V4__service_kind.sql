ALTER TABLE gateway_module
  ADD COLUMN service_kind VARCHAR(16) NOT NULL DEFAULT 'third_party';
