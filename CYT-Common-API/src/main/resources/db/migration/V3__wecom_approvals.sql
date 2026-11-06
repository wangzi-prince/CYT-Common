-- Business aliases and callback inbox are isolated by environment, system and mock/live mode.
CREATE TABLE gateway_wecom_form (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  env VARCHAR(32) NOT NULL,
  runtime VARCHAR(8) NOT NULL,
  `system` VARCHAR(32) NOT NULL,
  alias VARCHAR(64) NOT NULL,
  template_id VARCHAR(160) NOT NULL,
  field_aliases LONGTEXT NOT NULL,
  description VARCHAR(255) NOT NULL DEFAULT '',
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_wecom_form (env,runtime,`system`,alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE gateway_wecom_submission (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  env VARCHAR(32) NOT NULL,
  runtime VARCHAR(8) NOT NULL,
  `system` VARCHAR(32) NOT NULL,
  profile VARCHAR(64) NOT NULL,
  approval_no VARCHAR(64) NOT NULL,
  template_id VARCHAR(160) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_wecom_submission (env,runtime,`system`,request_id),
  INDEX ix_wecom_submission_number (env,runtime,profile,approval_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE gateway_wecom_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  env VARCHAR(32) NOT NULL,
  `system` VARCHAR(32) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  approval_no VARCHAR(64) NOT NULL DEFAULT '',
  payload_ciphertext LONGTEXT NOT NULL,
  delivery_state VARCHAR(16) NOT NULL DEFAULT 'pending',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_wecom_event (env,`system`,event_id),
  INDEX ix_wecom_event_poll (env,`system`,id),
  INDEX ix_wecom_event_delivery (delivery_state,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
