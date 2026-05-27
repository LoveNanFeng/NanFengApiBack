-- Card-code redemption migration.
-- This feature stores card codes in plaintext by product requirement.

CREATE TABLE IF NOT EXISTS `sys_redeem_card` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '卡密编号',
  `batch_no` varchar(64) NOT NULL COMMENT '生成批次号',
  `card_code` varchar(64) NOT NULL COMMENT '卡密明文',
  `card_type` varchar(32) NOT NULL COMMENT '卡密类型：BALANCE余额、GLOBAL_PACKAGE全站套餐、INTERFACE_PACKAGE接口套餐、POINT_PACKAGE点数套餐',
  `amount` decimal(12,4) NOT NULL DEFAULT 0.0000 COMMENT '余额卡密金额',
  `point_amount` bigint NOT NULL DEFAULT 0 COMMENT '点数套餐到账点数',
  `package_scope` varchar(32) DEFAULT NULL COMMENT '套餐范围：GLOBAL全站、INTERFACE接口、POINT点数',
  `package_id` bigint DEFAULT NULL COMMENT '套餐编号',
  `spec_id` bigint DEFAULT NULL COMMENT '接口套餐规格编号',
  `package_name` varchar(255) DEFAULT NULL COMMENT '套餐展示名称',
  `reward_valid_days` int NOT NULL DEFAULT 0 COMMENT '套餐有效天数快照，生成卡密时写入',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `used` tinyint NOT NULL DEFAULT 0 COMMENT '是否已使用：1已使用，0未使用',
  `used_user_id` bigint DEFAULT NULL COMMENT '使用用户编号',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间',
  `target_user_id` bigint DEFAULT NULL COMMENT '指定可兑换用户编号，空表示不限制',
  `target_username` varchar(64) DEFAULT NULL COMMENT '指定可兑换用户账号',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间，空表示不过期',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_redeem_card_code` (`card_code`),
  KEY `idx_sys_redeem_card_batch` (`batch_no`),
  KEY `idx_sys_redeem_card_type_used` (`card_type`, `used`, `status`),
  KEY `idx_sys_redeem_card_used_user_time` (`used_user_id`, `used_time`),
  KEY `idx_sys_redeem_card_target_user` (`target_user_id`, `used`, `status`),
  KEY `idx_sys_redeem_card_creator_time` (`creator_id`, `create_time`),
  KEY `idx_sys_redeem_card_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡密表';

SET @current_schema = DATABASE();
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @current_schema AND TABLE_NAME = 'sys_redeem_card' AND COLUMN_NAME = 'target_user_id') = 0,
  'ALTER TABLE `sys_redeem_card` ADD COLUMN `target_user_id` bigint DEFAULT NULL COMMENT ''指定可兑换用户编号，空表示不限制'' AFTER `used_time`',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @current_schema AND TABLE_NAME = 'sys_redeem_card' AND COLUMN_NAME = 'target_username') = 0,
  'ALTER TABLE `sys_redeem_card` ADD COLUMN `target_username` varchar(64) DEFAULT NULL COMMENT ''指定可兑换用户账号'' AFTER `target_user_id`',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @current_schema AND TABLE_NAME = 'sys_redeem_card' AND COLUMN_NAME = 'reward_valid_days') = 0,
  'ALTER TABLE `sys_redeem_card` ADD COLUMN `reward_valid_days` int NOT NULL DEFAULT 0 COMMENT ''套餐有效天数快照，生成卡密时写入'' AFTER `package_name`',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = @current_schema AND TABLE_NAME = 'sys_redeem_card' AND INDEX_NAME = 'idx_sys_redeem_card_target_user') = 0,
  'ALTER TABLE `sys_redeem_card` ADD KEY `idx_sys_redeem_card_target_user` (`target_user_id`, `used`, `status`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `sys_redeem_card_open_key` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '公开接口Key编号',
  `key_name` varchar(64) NOT NULL COMMENT 'Key名称',
  `km_key` varchar(64) NOT NULL COMMENT '公开接口调用Key',
  `card_type` varchar(32) NOT NULL COMMENT '公开类型：GLOBAL、INTERFACE、POINT、BALANCE',
  `type_code` varchar(32) NOT NULL COMMENT '类型编码：global、interface、point、balance',
  `amount` decimal(12,4) NOT NULL DEFAULT 0.0000 COMMENT '兼容保留字段，余额金额以公开接口money参数为准',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人编号',
  `last_used_time` datetime DEFAULT NULL COMMENT '最后调用时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_redeem_card_open_key` (`km_key`),
  KEY `idx_sys_redeem_card_open_key_type_status` (`card_type`, `status`),
  KEY `idx_sys_redeem_card_open_key_creator` (`creator_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡密公开接口Key表';

CREATE TABLE IF NOT EXISTS `sys_redeem_card_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志编号',
  `card_id` bigint NOT NULL COMMENT '卡密编号',
  `card_code` varchar(64) NOT NULL COMMENT '卡密明文',
  `card_type` varchar(32) NOT NULL COMMENT '卡密类型',
  `reward_summary` varchar(255) NOT NULL COMMENT '兑换奖励摘要',
  `user_id` bigint NOT NULL COMMENT '使用用户编号',
  `username` varchar(64) NOT NULL COMMENT '使用用户账号',
  `real_name` varchar(64) DEFAULT NULL COMMENT '使用用户姓名',
  `client_ip` varchar(64) DEFAULT NULL COMMENT '兑换来源IP',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '兑换时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_redeem_card_log_card` (`card_id`),
  KEY `idx_sys_redeem_card_log_user_time` (`user_id`, `create_time`),
  KEY `idx_sys_redeem_card_log_type_time` (`card_type`, `create_time`),
  KEY `idx_sys_redeem_card_log_code` (`card_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡密兑换日志表';

INSERT INTO `sys_menu`
  (`id`, `parent_id`, `name`, `path`, `component`, `redirect`, `title`, `icon`, `type`, `permission`, `sort_no`, `affix_tab`, `keep_alive`, `hide_in_menu`, `status`)
VALUES
  (1200, 0, 'RedeemCardManagement', '/redeem-card', NULL, '/redeem-card/generate', '卡密管理', 'mdi:ticket-confirmation-outline', 'catalog', NULL, 65, NULL, NULL, NULL, 1),
  (1201, 1200, 'RedeemCardGenerate', '/redeem-card/generate', '/redeem-card/generate/index', NULL, '生成卡密', 'mdi:ticket-percent-outline', 'menu', 'RedeemCard:Generate', 10, NULL, NULL, NULL, 1),
  (1202, 1200, 'RedeemCardLog', '/redeem-card/log', '/redeem-card/log/index', NULL, '卡密日志', 'mdi:clipboard-text-clock-outline', 'menu', 'RedeemCard:Log:List', 20, NULL, NULL, NULL, 1),
  (1203, 1201, 'RedeemCardCreate', '/redeem-card/generate/create', NULL, NULL, 'common.create', NULL, 'button', 'RedeemCard:Create', 11, NULL, NULL, 1, 1),
  (1204, 1200, 'RedeemCardOpen', '/redeem-card/open', '/redeem-card/open/index', NULL, '公开接口', 'mdi:api', 'menu', 'RedeemCard:Open:List', 30, NULL, NULL, NULL, 1),
  (1210, 0, 'UserRedeemCard', '/redeem', '/redeem-card/user/index', NULL, '卡密兑换', 'mdi:ticket-percent-outline', 'menu', 'RedeemCard:User:Redeem', 75, NULL, NULL, NULL, 1)
ON DUPLICATE KEY UPDATE
  `parent_id` = values(`parent_id`),
  `name` = values(`name`),
  `path` = values(`path`),
  `component` = values(`component`),
  `redirect` = values(`redirect`),
  `title` = values(`title`),
  `icon` = values(`icon`),
  `type` = values(`type`),
  `permission` = values(`permission`),
  `sort_no` = values(`sort_no`),
  `affix_tab` = values(`affix_tab`),
  `keep_alive` = values(`keep_alive`),
  `hide_in_menu` = values(`hide_in_menu`),
  `status` = values(`status`);

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, `id`
FROM `sys_menu`
WHERE `id` IN (1200, 1201, 1202, 1203, 1204);

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 2, `id`
FROM `sys_menu`
WHERE `id` IN (1210);
