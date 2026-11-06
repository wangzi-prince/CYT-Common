CREATE INDEX ix_calls_system_time ON gateway_call_log (`system`, called_at);
CREATE INDEX ix_calls_platform_time ON gateway_call_log (platform, called_at);
CREATE INDEX ix_calls_operation_time ON gateway_call_log (operation, called_at);
CREATE INDEX ix_audit_actor_time ON gateway_admin_audit (actor, created_at);
