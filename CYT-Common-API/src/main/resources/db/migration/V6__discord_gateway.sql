CREATE TABLE gateway_discord_session (
  env VARCHAR(32) NOT NULL,
  bot_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(160) NOT NULL DEFAULT '',
  sequence_no BIGINT NULL,
  resume_url VARCHAR(512) NOT NULL DEFAULT '',
  lease_owner VARCHAR(64) NOT NULL DEFAULT '',
  lease_until DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (env, bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE gateway_discord_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  env VARCHAR(32) NOT NULL,
  bot_id VARCHAR(32) NOT NULL,
  event_key VARCHAR(64) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  payload LONGTEXT NOT NULL,
  received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_discord_event (env, bot_id, event_key),
  INDEX ix_discord_event_cursor (env, bot_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
