-- WeChat Pay configuration migration.
-- Do not commit real APIv3 keys or merchant private keys into SQL files.

CREATE TABLE IF NOT EXISTS `sys_payment_wechat_config` (
  `id` tinyint NOT NULL COMMENT '配置编号',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用微信支付：1启用，0禁用',
  `app_id` varchar(64) DEFAULT NULL COMMENT '微信支付绑定的AppID',
  `mch_id` varchar(32) DEFAULT NULL COMMENT '微信支付商户号',
  `api_v3_key` varchar(64) DEFAULT NULL COMMENT '微信支付APIv3密钥',
  `merchant_serial_no` varchar(128) DEFAULT NULL COMMENT '商户API证书序列号',
  `merchant_private_key` text COMMENT '商户API证书私钥',
  `wechatpay_public_key_id` varchar(128) DEFAULT NULL COMMENT '微信支付平台公钥ID或平台证书序列号',
  `wechatpay_public_key` text COMMENT '微信支付平台公钥，用于回调验签',
  `notify_url` varchar(512) DEFAULT NULL COMMENT '异步通知地址',
  `native_pay_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用Native扫码支付：1启用，0禁用',
  `gateway_url` varchar(255) NOT NULL DEFAULT 'https://api.mch.weixin.qq.com' COMMENT '微信支付API网关地址',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信支付配置表';

INSERT INTO `sys_payment_wechat_config`
  (`id`, `enabled`, `gateway_url`, `native_pay_enabled`)
VALUES
  (1, 0, 'https://api.mch.weixin.qq.com', 1)
ON DUPLICATE KEY UPDATE
  `gateway_url` = values(`gateway_url`),
  `native_pay_enabled` = values(`native_pay_enabled`);

SET @has_recharge_status_expire_idx := (
  SELECT COUNT(1)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_payment_recharge_order'
    AND INDEX_NAME = 'idx_recharge_status_expire_time'
);
SET @ddl_recharge_status_expire_idx := IF(
  @has_recharge_status_expire_idx = 0,
  'ALTER TABLE `sys_payment_recharge_order` ADD KEY `idx_recharge_status_expire_time` (`status`, `expire_time`)',
  'SELECT 1'
);
PREPARE stmt_recharge_status_expire_idx FROM @ddl_recharge_status_expire_idx;
EXECUTE stmt_recharge_status_expire_idx;
DEALLOCATE PREPARE stmt_recharge_status_expire_idx;

UPDATE `sys_payment_recharge_order`
SET `expire_time` = DATE_ADD(`create_time`, INTERVAL 5 MINUTE)
WHERE `status` = 'PENDING'
  AND `expire_time` > DATE_ADD(`create_time`, INTERVAL 5 MINUTE);

ALTER TABLE `sys_payment_recharge_order`
  MODIFY COLUMN `pay_channel` varchar(32) NOT NULL DEFAULT 'ALIPAY' COMMENT '支付渠道：ALIPAY支付宝、WECHAT微信、BALANCE余额',
  MODIFY COLUMN `pay_product` varchar(32) NOT NULL COMMENT '支付产品：PAGE电脑网站、WAP手机网站、FACE当面付、NATIVE微信扫码',
  MODIFY COLUMN `alipay_method` varchar(64) NOT NULL COMMENT '第三方接口方法',
  MODIFY COLUMN `trade_no` varchar(128) DEFAULT NULL COMMENT '第三方交易号',
  MODIFY COLUMN `buyer_id` varchar(64) DEFAULT NULL COMMENT '第三方买家用户标识',
  MODIFY COLUMN `qr_code` varchar(1024) DEFAULT NULL COMMENT '扫码支付二维码内容',
  MODIFY COLUMN `notify_payload` mediumtext COMMENT '第三方支付通知原始参数';
