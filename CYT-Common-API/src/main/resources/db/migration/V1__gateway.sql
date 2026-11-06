-- Generated from the original SQLAlchemy models. MySQL 8.0.13+ / 8.4.
-- Identifiers and idempotency keys are case-sensitive. All timestamps are UTC.

CREATE TABLE `gateway_admin_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `actor` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `action` VARCHAR(32) NOT NULL,
  `target` VARCHAR(160) NOT NULL DEFAULT '',
  `detail` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX `ix_adminaudit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_call_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `trace_id` VARCHAR(40) NOT NULL,
  `system` VARCHAR(32) NOT NULL,
  `operation` VARCHAR(128) NOT NULL,
  `platform` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ok',
  `http_status` INT NOT NULL DEFAULT 200,
  `error_type` VARCHAR(32),
  `error_message` LONGTEXT,
  `latency_ms` INT NOT NULL DEFAULT 0,
  `tokens_in` INT,
  `tokens_out` INT,
  `cost_usd` DOUBLE NOT NULL DEFAULT 0.0,
  `idempotency_key` VARCHAR(128),
  `request_summary` LONGTEXT NOT NULL DEFAULT (''),
  `response_summary` LONGTEXT NOT NULL DEFAULT (''),
  `mock` BOOLEAN NOT NULL DEFAULT 0,
  `source` VARCHAR(16) NOT NULL DEFAULT 'live',
  `called_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX `ix_calllog_called_at` (`called_at`),
  INDEX `ix_calllog_platform` (`platform`),
  INDEX `ix_calllog_system` (`system`),
  INDEX `ix_calllog_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_callback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `system` VARCHAR(32) NOT NULL,
  `platform` VARCHAR(32) NOT NULL,
  `url` VARCHAR(512) NOT NULL,
  `secret_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `enabled` BOOLEAN NOT NULL DEFAULT 1,
  `created_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`system`,`platform`),
  INDEX `ix_gateway_callback_system` (`system`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_custom_api` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `key` VARCHAR(128) NOT NULL,
  `module_key` VARCHAR(64) NOT NULL DEFAULT '',
  `name` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `base_url` VARCHAR(255) NOT NULL,
  `method` VARCHAR(8) NOT NULL DEFAULT 'GET',
  `path` VARCHAR(512) NOT NULL DEFAULT '/',
  `auth_type` VARCHAR(16) NOT NULL DEFAULT 'none',
  `auth_name` VARCHAR(64) NOT NULL DEFAULT '',
  `secret_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `headers_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `body_template` LONGTEXT NOT NULL DEFAULT (''),
  `params_schema_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `response_path` VARCHAR(128) NOT NULL DEFAULT '',
  `pagination_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `retry_max` INT NOT NULL DEFAULT 1,
  `timeout_s` DOUBLE NOT NULL DEFAULT 30.0,
  `sensitive` BOOLEAN NOT NULL DEFAULT 0,
  `enabled` BOOLEAN NOT NULL DEFAULT 1,
  `created_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `category` VARCHAR(64) NOT NULL DEFAULT '',
  `sort_order` INT NOT NULL DEFAULT 0,
  UNIQUE (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_environment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `key` VARCHAR(32) NOT NULL,
  `label` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `is_production` BOOLEAN NOT NULL DEFAULT 0,
  `base_urls_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `extra_vars_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `enabled` BOOLEAN NOT NULL DEFAULT 1,
  `created_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_grant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `system` VARCHAR(32) NOT NULL,
  `operation_pattern` VARCHAR(128) NOT NULL,
  `effect` VARCHAR(8) NOT NULL DEFAULT 'allow',
  `quota` VARCHAR(32),
  `expires_at` DATETIME(6),
  `granted_by` VARCHAR(64) NOT NULL DEFAULT 'system',
  `note` VARCHAR(255) NOT NULL DEFAULT '',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`system`,`operation_pattern`),
  INDEX `ix_grant_system` (`system`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_grant_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `actor` VARCHAR(64) NOT NULL,
  `action` VARCHAR(16) NOT NULL,
  `system` VARCHAR(32) NOT NULL,
  `operation_pattern` VARCHAR(128) NOT NULL,
  `detail` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_idempotency` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `system` VARCHAR(32) NOT NULL,
  `key` VARCHAR(128) NOT NULL,
  `operation` VARCHAR(128) NOT NULL,
  `response_json` LONGTEXT NOT NULL,
  `params_hash` VARCHAR(64) NOT NULL DEFAULT '',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`system`,`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_module` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `key` VARCHAR(64) NOT NULL,
  `name` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `base_url` VARCHAR(255) NOT NULL,
  `auth_type` VARCHAR(24) NOT NULL DEFAULT 'none',
  `auth_name` VARCHAR(64) NOT NULL DEFAULT '',
  `secret_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `token_url` VARCHAR(255) NOT NULL DEFAULT '',
  `client_id_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `client_secret_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `refresh_token_ref` VARCHAR(64) NOT NULL DEFAULT '',
  `scopes` VARCHAR(255) NOT NULL DEFAULT '',
  `headers_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `timeout_s` DOUBLE NOT NULL DEFAULT 30.0,
  `enabled` BOOLEAN NOT NULL DEFAULT 1,
  `created_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_op_category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `platform` VARCHAR(32) NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 100,
  `note` VARCHAR(255) NOT NULL DEFAULT '',
  `updated_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`platform`,`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_op_order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `operation_key` VARCHAR(128) NOT NULL,
  `category` VARCHAR(64) NOT NULL DEFAULT '',
  `sort_order` INT NOT NULL DEFAULT 0,
  `updated_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`operation_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_secret` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `env` VARCHAR(32) NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  `ciphertext` LONGTEXT NOT NULL,
  `updated_by` VARCHAR(64) NOT NULL DEFAULT 'admin',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (`env`,`name`),
  INDEX `ix_secret_env` (`env`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_system` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `key` VARCHAR(32) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `enabled` BOOLEAN NOT NULL DEFAULT 1,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE INDEX `ix_gateway_system_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `gateway_system_apikey` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `system` VARCHAR(32) NOT NULL,
  `name` VARCHAR(64) NOT NULL DEFAULT '',
  `prefix` VARCHAR(16) NOT NULL,
  `secret_hash` VARCHAR(64) NOT NULL,
  `revoked` BOOLEAN NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `last_used_at` DATETIME(6),
  INDEX `ix_apikey_prefix` (`prefix`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE gateway_idempotency ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'complete';
