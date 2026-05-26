CREATE DATABASE IF NOT EXISTS `nanfeng_api_billing`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `nanfeng_api_billing`;

DROP TABLE IF EXISTS `sys_role_menu`;
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_menu`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_register_email_code`;
DROP TABLE IF EXISTS `sys_register_email_config`;
DROP TABLE IF EXISTS `sys_register_mobile_code`;
DROP TABLE IF EXISTS `sys_register_mobile_config`;
DROP TABLE IF EXISTS `sys_register_config`;
DROP TABLE IF EXISTS `sys_site_config`;
DROP TABLE IF EXISTS `sys_home_notice_config`;
DROP TABLE IF EXISTS `sys_friend_link_application`;
DROP TABLE IF EXISTS `sys_friend_link`;
DROP TABLE IF EXISTS `sys_friend_link_config`;
DROP TABLE IF EXISTS `sys_payment_recharge_order`;
DROP TABLE IF EXISTS `sys_payment_recharge_amount`;
DROP TABLE IF EXISTS `sys_payment_wechat_config`;
DROP TABLE IF EXISTS `sys_payment_alipay_config`;
DROP TABLE IF EXISTS `sys_user_package_interface`;
DROP TABLE IF EXISTS `sys_user_package_global`;
DROP TABLE IF EXISTS `sys_notice`;
DROP TABLE IF EXISTS `sys_package_interface_spec`;
DROP TABLE IF EXISTS `sys_package_interface`;
DROP TABLE IF EXISTS `sys_package_point`;
DROP TABLE IF EXISTS `sys_package_global`;
DROP TABLE IF EXISTS `sys_user_api_key`;
DROP TABLE IF EXISTS `sys_interface_call_log`;
DROP TABLE IF EXISTS `sys_interface_billing_rule`;
DROP TABLE IF EXISTS `sys_interface_api`;
DROP TABLE IF EXISTS `sys_user`;

CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户编号',
  `username` varchar(64) NOT NULL COMMENT '登录账号',
  `password_hash` varchar(100) NOT NULL COMMENT '加密密码哈希',
  `real_name` varchar(64) NOT NULL COMMENT '用户姓名',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像地址',
  `email` varchar(128) DEFAULT NULL COMMENT '邮箱',
  `mobile` varchar(32) DEFAULT NULL COMMENT '手机号',
  `home_path` varchar(128) NOT NULL DEFAULT '/workspace' COMMENT '登录后首页路径',
  `balance` decimal(12,4) NOT NULL DEFAULT 0.0000 COMMENT '账户余额',
  `points` bigint NOT NULL DEFAULT 0 COMMENT '账户点数',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `specified_response_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否指定调用返回信息：1是，0否',
  `specified_response_billable` tinyint NOT NULL DEFAULT 0 COMMENT '指定返回时是否正常计费：1计费，0不计费',
  `specified_response_body` mediumtext COMMENT '指定调用返回内容',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_username` (`username`),
  UNIQUE KEY `uk_sys_user_email` (`email`),
  UNIQUE KEY `uk_sys_user_mobile` (`mobile`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户表';

CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色编号',
  `role_key` varchar(64) NOT NULL COMMENT '角色标识',
  `role_name` varchar(64) NOT NULL COMMENT '角色名称',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_key` (`role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统角色表';

CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户编号',
  `role_id` bigint NOT NULL COMMENT '角色编号',
  PRIMARY KEY (`user_id`, `role_id`),
  KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表';

CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单编号',
  `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父级菜单编号',
  `name` varchar(128) NOT NULL COMMENT '前端路由名称',
  `path` varchar(255) NOT NULL COMMENT '前端路由路径',
  `component` varchar(255) DEFAULT NULL COMMENT '前端组件路径',
  `redirect` varchar(255) DEFAULT NULL COMMENT '重定向路径',
  `title` varchar(128) NOT NULL COMMENT '菜单标题或国际化键',
  `icon` varchar(128) DEFAULT NULL COMMENT '菜单图标',
  `type` varchar(32) NOT NULL DEFAULT 'menu' COMMENT '菜单类型：目录、菜单、内嵌页、外链、按钮',
  `permission` varchar(128) DEFAULT NULL COMMENT '按钮或接口权限码',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `affix_tab` tinyint DEFAULT NULL COMMENT '是否固定标签页：1是，0否',
  `keep_alive` tinyint DEFAULT NULL COMMENT '是否缓存页面：1是，0否',
  `hide_in_menu` tinyint DEFAULT NULL COMMENT '是否在菜单隐藏：1是，0否',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_menu_parent` (`parent_id`),
  KEY `idx_sys_menu_permission` (`permission`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统菜单表';

CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色编号',
  `menu_id` bigint NOT NULL COMMENT '菜单编号',
  PRIMARY KEY (`role_id`, `menu_id`),
  KEY `idx_sys_role_menu_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关联表';

  CREATE TABLE `sys_register_config` (
    `id` tinyint NOT NULL COMMENT '配置编号',
    `register_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否开放注册：1开放，0关闭',
    `default_user_qps` int NOT NULL DEFAULT 1 COMMENT '普通用户默认QPS上限',
    `register_gift_points` bigint NOT NULL DEFAULT 0 COMMENT '注册赠送点数',
    `verification_code_ip_minute_limit` int NOT NULL DEFAULT 5 COMMENT '验证码发送IP每分钟上限，0表示不限制',
    `verification_code_ip_hour_limit` int NOT NULL DEFAULT 20 COMMENT '验证码发送IP每小时上限，0表示不限制',
    `verification_code_ip_day_limit` int NOT NULL DEFAULT 50 COMMENT '验证码发送IP每日上限，0表示不限制',
    `register_ip_hour_limit` int NOT NULL DEFAULT 10 COMMENT '注册提交IP每小时上限，0表示不限制',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册总开关配置表';

CREATE TABLE `sys_home_notice_config` (
  `id` tinyint NOT NULL COMMENT '配置编号，固定为1，全站仅一条首页滚动公告',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用首页滚动公告：1启用，0禁用',
  `content` varchar(300) NOT NULL DEFAULT '' COMMENT '首页滚动公告内容',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_sys_home_notice_config_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首页滚动公告单例配置表';

CREATE TABLE `sys_site_config` (
  `id` tinyint NOT NULL COMMENT '配置编号，固定为1，全站仅一条站点配置',
  `site_name` varchar(64) NOT NULL DEFAULT 'NanFengAPI' COMMENT '网站名称',
  `logo_url` varchar(1024) DEFAULT NULL COMMENT '网站Logo地址',
  `slogan` varchar(120) DEFAULT NULL COMMENT '网站标语',
  `description` varchar(255) DEFAULT NULL COMMENT '网站描述',
  `contact_email` varchar(128) DEFAULT NULL COMMENT '联系邮箱',
  `contact_phone` varchar(64) DEFAULT NULL COMMENT '联系电话',
  `contact_qq` varchar(64) DEFAULT NULL COMMENT '联系QQ',
  `contact_wechat` varchar(64) DEFAULT NULL COMMENT '联系微信',
  `contact_address` varchar(255) DEFAULT NULL COMMENT '联系地址',
  `icp` varchar(128) DEFAULT NULL COMMENT '备案信息',
  `copyright` varchar(128) DEFAULT NULL COMMENT '版权信息',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_sys_site_config_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点单例配置表';

CREATE TABLE `sys_friend_link_config` (
  `id` tinyint NOT NULL COMMENT '配置编号，固定为1，全站仅一条友链配置',
  `apply_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否允许用户申请友链：1允许，0关闭',
  `apply_notice` varchar(300) DEFAULT NULL COMMENT '友链申请说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_sys_friend_link_config_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='友链单例配置表';

CREATE TABLE `sys_friend_link` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '友链编号',
  `site_name` varchar(80) NOT NULL COMMENT '网站名称',
  `site_url` varchar(1024) NOT NULL COMMENT '网站地址',
  `normalized_site_url` varchar(255) NOT NULL COMMENT '归一化网站域名，用于去重',
  `logo_url` varchar(1024) DEFAULT NULL COMMENT '网站Logo地址',
  `description` varchar(200) DEFAULT NULL COMMENT '网站描述',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1展示，0隐藏',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  `applicant_id` bigint DEFAULT NULL COMMENT '申请用户编号',
  `application_id` bigint DEFAULT NULL COMMENT '来源申请编号',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人编号',
  `updater_id` bigint DEFAULT NULL COMMENT '更新人编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_friend_link_normalized_url` (`normalized_site_url`),
  KEY `idx_sys_friend_link_status_sort` (`status`, `sort_no`, `id`),
  KEY `idx_sys_friend_link_applicant` (`applicant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='友情链接表';

CREATE TABLE `sys_friend_link_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '友链申请编号',
  `user_id` bigint NOT NULL COMMENT '申请用户编号',
  `site_name` varchar(80) NOT NULL COMMENT '网站名称',
  `site_url` varchar(1024) NOT NULL COMMENT '网站地址',
  `normalized_site_url` varchar(255) NOT NULL COMMENT '归一化网站域名，用于去重',
  `logo_url` varchar(1024) DEFAULT NULL COMMENT '网站Logo地址',
  `description` varchar(200) DEFAULT NULL COMMENT '网站描述',
  `contact_name` varchar(64) NOT NULL COMMENT '联系人姓名',
  `contact_email` varchar(128) NOT NULL COMMENT '联系邮箱',
  `contact_qq` varchar(64) DEFAULT NULL COMMENT 'QQ或微信',
  `backlink_url` varchar(1024) DEFAULT NULL COMMENT '已放置本站链接的页面',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核、APPROVED已通过、REJECTED已驳回',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT '驳回原因',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人编号',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_friend_link_application_user` (`user_id`),
  UNIQUE KEY `uk_friend_link_application_normalized_url` (`normalized_site_url`),
  KEY `idx_friend_link_application_status_time` (`status`, `create_time`),
  KEY `idx_friend_link_application_reviewer` (`reviewer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='友情链接申请表';

CREATE TABLE `sys_register_email_config` (
  `id` tinyint NOT NULL COMMENT '配置编号',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用邮箱注册：1启用，0禁用',
  `smtp_server` varchar(255) DEFAULT NULL COMMENT 'SMTP服务器',
  `smtp_port` int DEFAULT NULL COMMENT 'SMTP端口',
  `sender_email` varchar(128) DEFAULT NULL COMMENT '发件邮箱',
  `auth_code` varchar(255) DEFAULT NULL COMMENT '邮箱授权码',
  `sender_name` varchar(64) DEFAULT NULL COMMENT '发件人名称',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮箱注册配置表';

CREATE TABLE `sys_register_email_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '验证码编号',
  `email` varchar(128) NOT NULL COMMENT '邮箱',
  `code_hash` varchar(64) NOT NULL COMMENT '验证码哈希',
  `scene` varchar(32) NOT NULL DEFAULT 'register' COMMENT '验证码场景',
  `used` tinyint NOT NULL DEFAULT 0 COMMENT '是否已使用：1是，0否',
  `fail_count` int NOT NULL DEFAULT 0 COMMENT '失败次数',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间',
  PRIMARY KEY (`id`),
  KEY `idx_register_email_code_email_scene` (`email`, `scene`),
  KEY `idx_register_email_code_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册邮箱验证码表';

CREATE TABLE `sys_register_mobile_config` (
  `id` tinyint NOT NULL COMMENT '配置编号',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用手机号注册：1启用，0禁用',
  `provider` varchar(32) NOT NULL DEFAULT 'aliyun' COMMENT '短信厂商：aliyun阿里云',
  `access_key_id` varchar(128) DEFAULT NULL COMMENT 'AccessKey ID',
  `access_key_secret` varchar(255) DEFAULT NULL COMMENT 'AccessKey Secret',
  `sign_name` varchar(128) DEFAULT NULL COMMENT '短信签名',
  `template_id` varchar(128) DEFAULT NULL COMMENT '短信模板编号',
  `region` varchar(64) DEFAULT NULL COMMENT '地域',
  `endpoint` varchar(255) DEFAULT NULL COMMENT '接口端点',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手机号注册配置表';

CREATE TABLE `sys_register_mobile_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '验证码编号',
  `mobile` varchar(32) NOT NULL COMMENT '手机号',
  `code_hash` varchar(64) NOT NULL COMMENT '验证码哈希',
  `scene` varchar(32) NOT NULL DEFAULT 'register' COMMENT '验证码场景',
  `used` tinyint NOT NULL DEFAULT 0 COMMENT '是否已使用：1是，0否',
  `fail_count` int NOT NULL DEFAULT 0 COMMENT '失败次数',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间',
  PRIMARY KEY (`id`),
  KEY `idx_register_mobile_code_mobile_scene` (`mobile`, `scene`),
  KEY `idx_register_mobile_code_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册手机验证码表';

CREATE TABLE `sys_payment_alipay_config` (
  `id` tinyint NOT NULL COMMENT '配置编号',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用支付宝：1启用，0禁用',
  `app_id` varchar(64) DEFAULT NULL COMMENT '支付宝开放平台应用ID',
  `gateway_url` varchar(255) NOT NULL DEFAULT 'https://openapi.alipay.com/gateway.do' COMMENT '支付宝网关地址',
  `merchant_private_key` text COMMENT '应用私钥',
  `alipay_public_key` text COMMENT '支付宝公钥',
  `notify_url` varchar(512) DEFAULT NULL COMMENT '异步通知地址',
  `return_url` varchar(512) DEFAULT NULL COMMENT '同步跳转地址',
  `website_pay_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用电脑网站支付：1启用，0禁用',
  `wap_pay_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用手机网站支付：1启用，0禁用',
  `face_pay_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用当面付扫码支付：1启用，0禁用',
  `sign_type` varchar(16) NOT NULL DEFAULT 'RSA2' COMMENT '签名方式',
  `charset_name` varchar(32) NOT NULL DEFAULT 'UTF-8' COMMENT '字符集',
  `format_type` varchar(16) NOT NULL DEFAULT 'JSON' COMMENT '响应格式',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付宝支付配置表';

CREATE TABLE `sys_payment_wechat_config` (
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

CREATE TABLE `sys_payment_recharge_amount` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '充值金额配置编号',
  `amount` decimal(12,4) NOT NULL COMMENT '充值支付金额',
  `gift_amount` decimal(12,4) NOT NULL DEFAULT 0.0000 COMMENT '赠送金额',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_recharge_amount` (`amount`),
  KEY `idx_payment_recharge_amount_status_sort` (`status`, `sort_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='充值金额配置表';

CREATE TABLE `sys_payment_recharge_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '充值订单编号',
  `order_no` varchar(64) NOT NULL COMMENT '商户订单号',
  `user_id` bigint NOT NULL COMMENT '充值用户编号',
  `order_type` varchar(32) NOT NULL DEFAULT 'RECHARGE' COMMENT '订单类型',
  `biz_id` bigint DEFAULT NULL COMMENT '业务编号',
  `biz_name` varchar(128) DEFAULT NULL COMMENT '业务名称',
  `amount` decimal(12,4) NOT NULL COMMENT '充值金额',
  `gift_amount` decimal(12,4) NOT NULL DEFAULT 0.0000 COMMENT '充值赠送金额',
  `pay_channel` varchar(32) NOT NULL DEFAULT 'ALIPAY' COMMENT '支付渠道：ALIPAY支付宝、WECHAT微信、BALANCE余额',
  `pay_product` varchar(32) NOT NULL COMMENT '支付产品：PAGE电脑网站、WAP手机网站、FACE当面付、NATIVE微信扫码',
  `alipay_method` varchar(64) NOT NULL COMMENT '第三方接口方法',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING待支付、PAID已支付、FAILED失败、CLOSED关闭',
  `subject` varchar(128) NOT NULL COMMENT '订单标题',
  `body` varchar(255) DEFAULT NULL COMMENT '订单描述',
  `trade_no` varchar(128) DEFAULT NULL COMMENT '第三方交易号',
  `buyer_id` varchar(64) DEFAULT NULL COMMENT '第三方买家用户标识',
  `qr_code` varchar(1024) DEFAULT NULL COMMENT '扫码支付二维码内容',
  `client_type` varchar(16) NOT NULL DEFAULT 'AUTO' COMMENT '客户端类型：AUTO、DESKTOP、MOBILE',
  `notify_payload` mediumtext COMMENT '第三方支付通知原始参数',
  `paid_time` datetime DEFAULT NULL COMMENT '支付完成时间',
  `expire_time` datetime NOT NULL COMMENT '订单过期时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_order_no` (`order_no`),
  KEY `idx_recharge_user` (`user_id`),
  KEY `idx_recharge_order_type` (`order_type`),
  KEY `idx_recharge_status` (`status`),
  KEY `idx_recharge_status_expire_time` (`status`, `expire_time`),
  KEY `idx_recharge_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户余额充值订单表';

CREATE TABLE `sys_interface_api` (
  `avatar_url` varchar(1024) DEFAULT NULL COMMENT '接口头像地址',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '接口编号',
  `api_code` varchar(64) NOT NULL COMMENT '接口唯一编码',
  `name` varchar(128) NOT NULL COMMENT '接口名称',
  `request_url` varchar(1024) NOT NULL COMMENT '第三方接口地址',
  `polling_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否开启上游轮询：1开启，0关闭',
  `polling_mode` varchar(16) NOT NULL DEFAULT 'ROUND_ROBIN' COMMENT '轮询方式：ROUND_ROBIN普通轮询，PRIMARY主接口',
  `upstream_urls` text COMMENT '上游接口地址列表JSON数组，支持每个上游独立配置响应校验',
  `polling_check_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否开启轮询响应JSON字段校验：1开启，0关闭',
  `polling_check_field` varchar(128) DEFAULT 'code' COMMENT '轮询响应校验JSON字段路径，例如code或data.status',
  `polling_check_expected` varchar(255) DEFAULT '200' COMMENT '轮询响应校验期望值，不匹配则切换下一上游',
  `request_method` varchar(16) NOT NULL COMMENT '请求方式：GET、POST、GET_POST',
  `price` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '接口价格',
  `point_price` bigint NOT NULL DEFAULT 0 COMMENT '单次调用扣除点数',
  `is_top` tinyint NOT NULL DEFAULT 0 COMMENT '是否置顶：1置顶，0普通',
  `is_featured` tinyint NOT NULL DEFAULT 0 COMMENT '是否精选：首页展示开关，1精选，0普通',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '描述',
  `doc_summary` varchar(500) DEFAULT NULL COMMENT '公开接口文档描述，未配置时使用描述',
  `doc_response_type` varchar(32) NOT NULL DEFAULT 'JSON' COMMENT '公开接口文档返回方式：JSON、TEXT、XML、HTML、FILE',
  `doc_preferred_method` varchar(16) DEFAULT NULL COMMENT '公开接口文档推荐请求方式：GET、POST',
  `doc_request_params` text COMMENT '公开接口文档请求参数JSON数组，不包含密钥',
  `doc_response_fields` text COMMENT '公开接口文档返回字段JSON数组',
  `doc_response_example` mediumtext COMMENT '公开接口文档返回预览示例',
  `doc_status_codes` text COMMENT '公开接口文档状态码JSON数组',
  `doc_notice` varchar(500) DEFAULT NULL COMMENT '公开接口文档免责声明或提示',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_interface_api_code` (`api_code`),
  KEY `idx_sys_interface_api_top` (`is_top`, `id`),
  KEY `idx_sys_interface_api_featured` (`status`, `is_featured`, `is_top`, `id`),
  KEY `idx_sys_interface_api_status` (`status`),
  KEY `idx_sys_interface_api_method` (`request_method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口配置表';

CREATE TABLE `sys_interface_billing_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '扣费规则编号',
  `interface_id` bigint NOT NULL COMMENT '接口编号',
  `field_name` varchar(128) NOT NULL COMMENT '返回字段名，默认code，支持响应JSON字段路径',
  `operator` varchar(16) NOT NULL COMMENT '比较方式：EQ等于、NE不等于、GT大于、LT小于、CONTAINS包含',
  `expected_value` varchar(255) NOT NULL COMMENT '期望值',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_interface_billing_rule_api` (`interface_id`),
  KEY `idx_interface_billing_rule_sort` (`interface_id`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口扣费规则表';

CREATE TABLE `sys_interface_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '调用日志编号',
  `user_id` bigint NOT NULL COMMENT '调用用户编号',
  `interface_id` bigint NOT NULL COMMENT '接口编号',
  `request_method` varchar(16) NOT NULL COMMENT '实际请求方式',
  `request_params` text COMMENT '请求参数',
  `client_ip` varchar(64) DEFAULT NULL COMMENT '客户端真实IP',
  `client_region` varchar(255) DEFAULT NULL COMMENT '客户端IP归属完整地区',
  `client_country` varchar(64) DEFAULT NULL COMMENT '客户端IP归属国家或地区',
  `client_province` varchar(64) DEFAULT NULL COMMENT '客户端IP归属省级地区',
  `client_province_code` varchar(16) DEFAULT NULL COMMENT '客户端IP归属省级行政区划代码',
  `client_city` varchar(64) DEFAULT NULL COMMENT '客户端IP归属城市',
  `client_isp` varchar(64) DEFAULT NULL COMMENT '客户端网络运营商',
  `client_geo_source` varchar(32) DEFAULT NULL COMMENT '客户端归属来源：HEADER、LOCAL、UNKNOWN',
  `upstream_url` varchar(1024) DEFAULT NULL COMMENT '本次实际请求的上游地址',
  `upstream_switched` tinyint NOT NULL DEFAULT 0 COMMENT '是否发生上游切换：1是，0否',
  `polling_mode` varchar(16) NOT NULL DEFAULT 'SINGLE' COMMENT '本次使用的轮询模式：SINGLE、ROUND_ROBIN、PRIMARY',
  `response_status` int DEFAULT NULL COMMENT '响应状态码',
  `response_body` mediumtext COMMENT '响应内容',
  `success` tinyint NOT NULL DEFAULT 0 COMMENT '是否成功：1成功，0失败',
  `billable` tinyint NOT NULL DEFAULT 0 COMMENT '是否计费：1计费，0不计费',
  `charge_amount` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '本次计费金额',
  `charge_type` varchar(32) NOT NULL DEFAULT 'FREE' COMMENT '扣费来源：POINT点数、BALANCE余额、MEMBER会员、FREE免费、ADMIN管理员测试',
  `charge_scope` varchar(32) NOT NULL DEFAULT 'FREE' COMMENT '实际扣费层级：GLOBAL全站套餐、INTERFACE接口套餐、POINT点数、BALANCE余额、FREE免费、ADMIN管理员测试',
  `charge_package_id` bigint DEFAULT NULL COMMENT '扣费套餐开通记录编号',
  `charge_rule_snapshot` text COMMENT '扣费规则快照',
  `elapsed_ms` bigint NOT NULL DEFAULT 0 COMMENT '调用耗时毫秒',
  `error_message` varchar(500) DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_interface_call_log_user` (`user_id`),
  KEY `idx_sys_interface_call_log_interface` (`interface_id`),
  KEY `idx_sys_interface_call_log_user_interface_time` (`user_id`, `interface_id`, `create_time`),
  KEY `idx_sys_interface_call_log_user_time` (`user_id`, `create_time`),
  KEY `idx_sys_interface_call_log_client_ip` (`client_ip`),
  KEY `idx_sys_interface_call_log_region` (`client_province_code`, `client_province`),
  KEY `idx_sys_interface_call_log_charge_scope` (`charge_scope`, `charge_package_id`),
  KEY `idx_sys_interface_call_log_scope_package_time` (`charge_scope`, `charge_package_id`, `create_time`),
  KEY `idx_sys_interface_call_log_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口调用日志表';

CREATE TABLE `sys_user_api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '密钥编号',
  `user_id` bigint NOT NULL COMMENT '用户编号',
  `interface_id` bigint DEFAULT NULL COMMENT '接口编号',
  `key_scope` varchar(16) NOT NULL DEFAULT 'INTERFACE' COMMENT '密钥范围：INTERFACE接口、GLOBAL全站',
  `secret_key` char(24) NOT NULL COMMENT '接口调用密钥',
  `ip_whitelist` text COMMENT 'IP白名单，支持换行或逗号分隔',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_api_key_user_interface` (`user_id`, `interface_id`),
  UNIQUE KEY `uk_user_api_key_secret_key` (`secret_key`),
  KEY `idx_user_api_key_user` (`user_id`),
  KEY `idx_user_api_key_interface` (`interface_id`),
  KEY `idx_user_api_key_scope` (`key_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户接口密钥表';

CREATE TABLE `sys_package_global` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '套餐编号',
  `name` varchar(128) NOT NULL COMMENT '套餐名称',
  `price` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '套餐价格',
  `valid_days` int NOT NULL DEFAULT 30 COMMENT '套餐有效天数',
  `daily_limit` int NOT NULL DEFAULT 0 COMMENT '每日调用上限，0表示无限调用',
  `qps_limit` int NOT NULL DEFAULT 0 COMMENT '每秒调用QPS上限，0表示不限制',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_package_global_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全站套餐表';

CREATE TABLE `sys_package_interface` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '接口套餐编号',
  `interface_id` bigint NOT NULL COMMENT '接口编号',
  `name` varchar(128) NOT NULL COMMENT '套餐名称',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_package_interface_api` (`interface_id`),
  KEY `idx_package_interface_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口套餐表';

CREATE TABLE `sys_package_point` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '点数套餐编号',
  `name` varchar(128) NOT NULL COMMENT '套餐名称',
  `price` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '套餐价格',
  `point_amount` bigint NOT NULL DEFAULT 0 COMMENT '套餐点数',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_package_point_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点数套餐表';

CREATE TABLE `sys_package_interface_spec` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '套餐规格编号',
  `package_id` bigint NOT NULL COMMENT '接口套餐编号',
  `spec_name` varchar(128) NOT NULL COMMENT '规格名称',
  `price` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '规格价格',
  `valid_days` int NOT NULL DEFAULT 30 COMMENT '规格有效天数',
  `daily_limit` int NOT NULL DEFAULT 0 COMMENT '每日调用上限，0表示无限调用',
  `qps_limit` int NOT NULL DEFAULT 0 COMMENT '每秒调用QPS上限，0表示不限制',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_package_interface_spec_package` (`package_id`),
  KEY `idx_package_interface_spec_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口套餐规格表';

CREATE TABLE `sys_user_package_global` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户全站套餐编号',
  `user_id` bigint NOT NULL COMMENT '用户编号',
  `package_id` bigint NOT NULL COMMENT '全站套餐编号',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `start_time` datetime DEFAULT NULL COMMENT '生效时间',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_package_global_user` (`user_id`),
  KEY `idx_user_package_global_package` (`package_id`),
  KEY `idx_user_package_global_time` (`start_time`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户全站套餐开通表';

CREATE TABLE `sys_user_package_interface` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户接口套餐编号',
  `user_id` bigint NOT NULL COMMENT '用户编号',
  `interface_id` bigint NOT NULL COMMENT '接口编号',
  `package_id` bigint NOT NULL COMMENT '接口套餐编号',
  `spec_id` bigint NOT NULL COMMENT '套餐规格编号',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `start_time` datetime DEFAULT NULL COMMENT '生效时间',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_package_interface_user` (`user_id`),
  KEY `idx_user_package_interface_api` (`interface_id`),
  KEY `idx_user_package_interface_spec` (`spec_id`),
  KEY `idx_user_package_interface_time` (`start_time`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户接口套餐开通表';

CREATE TABLE `sys_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '公告编号',
  `title` varchar(100) NOT NULL COMMENT '公告标题',
  `content_html` mediumtext NOT NULL COMMENT '公告富文本内容',
  `summary` varchar(255) DEFAULT NULL COMMENT '公告摘要',
  `is_top` tinyint NOT NULL DEFAULT 0 COMMENT '是否置顶：1置顶，0普通',
  `is_popup` tinyint NOT NULL DEFAULT 0 COMMENT '是否每日弹窗：1弹窗，0不弹窗',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1发布，0隐藏',
  `publish_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人编号',
  `updater_id` bigint DEFAULT NULL COMMENT '更新人编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_notice_status_top_time` (`status`, `is_top`, `publish_time`),
  KEY `idx_sys_notice_status_popup_time` (`status`, `is_popup`, `publish_time`),
  KEY `idx_sys_notice_publish_time` (`publish_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告表';

INSERT INTO `sys_user`
  (`id`, `username`, `password_hash`, `real_name`, `avatar`, `home_path`, `status`)
VALUES
  (1, 'nanfeng', '$2a$10$JXmV1cjkkdmh3s/fRfPjKuaoOFtwQrxHmWoJLVoK5SqGcqG/RtBb6', '管理员', NULL, '/workspace', 1);

INSERT INTO `sys_role` (`id`, `role_key`, `role_name`, `status`)
VALUES
  (1, 'admin', '系统管理员', 1),
  (2, 'user', '普通用户', 1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
VALUES (1, 1);

  INSERT INTO `sys_register_config`
    (`id`, `register_enabled`, `default_user_qps`, `register_gift_points`, `verification_code_ip_minute_limit`, `verification_code_ip_hour_limit`, `verification_code_ip_day_limit`, `register_ip_hour_limit`)
  VALUES
    (1, 1, 1, 0, 5, 20, 50, 10);

INSERT INTO `sys_home_notice_config` (`id`, `enabled`, `content`)
VALUES (1, 0, '');

INSERT INTO `sys_site_config`
  (`id`, `site_name`, `logo_url`, `slogan`, `description`, `contact_email`, `contact_phone`, `contact_qq`, `contact_wechat`, `contact_address`, `icp`, `copyright`)
VALUES
  (1, 'NanFengAPI', NULL, '稳定、清晰、可运营的 API 服务平台', '统一管理接口、Key、套餐、计费与调用日志。', '', '', '', '', '', '', '© 2026 NanFengAPI. All rights reserved.');

INSERT INTO `sys_friend_link_config` (`id`, `apply_enabled`, `apply_notice`)
VALUES (1, 1, '请先在贵站添加本站链接，再提交友链申请。');

INSERT INTO `sys_register_email_config` (`id`, `enabled`)
VALUES (1, 0);

INSERT INTO `sys_register_mobile_config` (`id`, `enabled`, `provider`, `region`, `endpoint`)
VALUES (1, 0, 'aliyun', 'cn-hangzhou', 'dypnsapi.aliyuncs.com');

INSERT INTO `sys_payment_alipay_config`
  (`id`, `enabled`, `gateway_url`, `website_pay_enabled`, `wap_pay_enabled`, `face_pay_enabled`, `sign_type`, `charset_name`, `format_type`)
VALUES
  (1, 0, 'https://openapi.alipay.com/gateway.do', 1, 1, 0, 'RSA2', 'UTF-8', 'JSON');

INSERT INTO `sys_payment_wechat_config`
  (`id`, `enabled`, `gateway_url`, `native_pay_enabled`)
VALUES
  (1, 0, 'https://api.mch.weixin.qq.com', 1);

INSERT INTO `sys_payment_recharge_amount`
  (`id`, `amount`, `gift_amount`, `status`, `sort_no`, `remark`)
VALUES
  (1, 10.0000, 0.0000, 1, 10, '默认充值金额'),
  (2, 30.0000, 0.0000, 1, 20, '默认充值金额'),
  (3, 50.0000, 0.0000, 1, 30, '默认充值金额'),
  (4, 100.0000, 0.0000, 1, 40, '默认充值金额');

INSERT INTO `sys_menu`
  (`id`, `parent_id`, `name`, `path`, `component`, `redirect`, `title`, `icon`, `type`, `permission`, `sort_no`, `affix_tab`, `keep_alive`, `hide_in_menu`, `status`)
VALUES
  (100, 0, 'Dashboard', '/dashboard', NULL, '/workspace', 'page.dashboard.title', 'lucide:layout-dashboard', 'catalog', NULL, -1, NULL, NULL, 1, 0),
  (101, 100, 'Analytics', '/analytics', '/dashboard/analytics/index', NULL, 'page.dashboard.analytics', 'lucide:area-chart', 'menu', NULL, 0, 1, 1, 1, 0),
  (102, 0, 'Workspace', '/workspace', '/dashboard/workspace/index', NULL, 'page.dashboard.workspace', 'carbon:workspace', 'menu', NULL, 10, 1, 1, NULL, 1),
  (500, 0, 'InterfaceManagement', '/interface', NULL, '/interface/list', 'system.interface.title', 'mdi:api', 'catalog', NULL, 30, NULL, NULL, NULL, 1),
  (501, 500, 'InterfaceList', '/interface/list', '/interface/list', NULL, 'system.interface.listTitle', 'mdi:api', 'menu', 'Interface:Api:List', 10, NULL, NULL, NULL, 1),
  (502, 501, 'InterfaceCreate', '/interface/list/create', NULL, NULL, 'common.create', NULL, 'button', 'Interface:Api:Create', 11, NULL, NULL, 1, 1),
  (503, 501, 'InterfaceEdit', '/interface/list/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Interface:Api:Edit', 12, NULL, NULL, 1, 1),
  (504, 501, 'InterfaceDelete', '/interface/list/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Interface:Api:Delete', 13, NULL, NULL, 1, 1),
  (505, 501, 'InterfaceInvoke', '/interface/list/invoke', NULL, NULL, 'system.interface.invoke', NULL, 'button', 'Interface:Api:Invoke', 14, NULL, NULL, 1, 1),
  (507, 501, 'InterfaceBillingRule', '/interface/list/billing-rule', NULL, NULL, 'system.interface.billingRule', NULL, 'button', 'Interface:Api:BillingRule', 15, NULL, NULL, 1, 1),
  (506, 0, 'UserInterfaceList', '/interface/list', '/interface/list', NULL, 'system.interface.listTitle', 'mdi:api', 'menu', 'Interface:Api:List', 40, NULL, NULL, NULL, 1),
  (508, 500, 'InterfaceCallLog', '/interface/log', '/interface/log', NULL, 'system.interface.callLogTitle', 'mdi:clipboard-text-clock', 'menu', 'Interface:CallLog:List', 20, NULL, NULL, NULL, 1),
  (509, 0, 'UserCallLog', '/interface/log', '/interface/log', NULL, 'system.interface.callLogTitle', 'mdi:clipboard-text-clock', 'menu', 'Interface:CallLog:List', 50, NULL, NULL, NULL, 1),
  (600, 0, 'KeyManagement', '/key', '/key/list', NULL, 'system.key.title', 'mdi:key-chain', 'menu', 'Key:Api:List', 30, NULL, NULL, NULL, 1),
  (601, 600, 'KeyCreate', '/key/create', NULL, NULL, 'common.create', NULL, 'button', 'Key:Api:Create', 1, NULL, NULL, 1, 1),
  (602, 600, 'KeyDelete', '/key/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Key:Api:Delete', 2, NULL, NULL, 1, 1),
  (603, 600, 'KeyRegenerate', '/key/regenerate', NULL, NULL, 'system.key.regenerate', NULL, 'button', 'Key:Api:Regenerate', 3, NULL, NULL, 1, 1),
  (604, 600, 'KeyStatus', '/key/status', NULL, NULL, 'system.key.status', NULL, 'button', 'Key:Api:Status', 4, NULL, NULL, 1, 1),
  (605, 600, 'KeyIpWhitelist', '/key/ip-whitelist', NULL, NULL, 'system.key.ipWhitelistManage', NULL, 'button', 'Key:Api:IpWhitelist', 5, NULL, NULL, 1, 1),
  (700, 0, 'PackageManagement', '/package', NULL, '/package/global', 'system.package.title', 'mdi:package-variant-closed', 'catalog', NULL, 40, NULL, NULL, NULL, 1),
  (701, 700, 'GlobalPackage', '/package/global', '/package/global/list', NULL, 'system.package.globalTitle', 'mdi:web', 'menu', 'Package:Global:List', 10, NULL, NULL, NULL, 1),
  (702, 700, 'InterfacePackage', '/package/interface', '/package/interface/list', NULL, 'system.package.interfaceTitle', 'mdi:package-variant', 'menu', 'Package:Interface:List', 20, NULL, NULL, NULL, 1),
  (711, 700, 'PointPackage', '/package/point', '/package/point/list', NULL, 'system.package.pointTitle', 'mdi:database-plus', 'menu', 'Package:Point:List', 30, NULL, NULL, NULL, 1),
  (703, 701, 'GlobalPackageCreate', '/package/global/create', NULL, NULL, 'common.create', NULL, 'button', 'Package:Global:Create', 11, NULL, NULL, 1, 1),
  (704, 701, 'GlobalPackageEdit', '/package/global/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Package:Global:Edit', 12, NULL, NULL, 1, 1),
  (705, 701, 'GlobalPackageDelete', '/package/global/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Package:Global:Delete', 13, NULL, NULL, 1, 1),
  (709, 701, 'GlobalPackageOpen', '/package/global/open', NULL, NULL, 'system.package.openPackage', NULL, 'button', 'Package:Global:Open', 14, NULL, NULL, 1, 1),
  (706, 702, 'InterfacePackageCreate', '/package/interface/create', NULL, NULL, 'common.create', NULL, 'button', 'Package:Interface:Create', 21, NULL, NULL, 1, 1),
  (707, 702, 'InterfacePackageEdit', '/package/interface/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Package:Interface:Edit', 22, NULL, NULL, 1, 1),
  (708, 702, 'InterfacePackageDelete', '/package/interface/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Package:Interface:Delete', 23, NULL, NULL, 1, 1),
  (710, 702, 'InterfacePackageOpen', '/package/interface/open', NULL, NULL, 'system.package.openPackage', NULL, 'button', 'Package:Interface:Open', 24, NULL, NULL, 1, 1),
  (712, 711, 'PointPackageCreate', '/package/point/create', NULL, NULL, 'common.create', NULL, 'button', 'Package:Point:Create', 31, NULL, NULL, 1, 1),
  (713, 711, 'PointPackageEdit', '/package/point/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Package:Point:Edit', 32, NULL, NULL, 1, 1),
  (714, 711, 'PointPackageDelete', '/package/point/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Package:Point:Delete', 33, NULL, NULL, 1, 1),
  (800, 0, 'PackagePurchase', '/purchase', NULL, '/purchase/global', 'system.package.purchaseTitle', 'mdi:cart-outline', 'catalog', NULL, 20, NULL, NULL, NULL, 1),
  (801, 800, 'UserGlobalPackage', '/purchase/global', '/purchase/global/list', NULL, 'system.package.buyGlobalTitle', 'mdi:shopping-outline', 'menu', 'Package:Mall:Global', 10, NULL, NULL, NULL, 1),
  (802, 800, 'UserInterfacePackage', '/purchase/interface', '/purchase/interface/list', NULL, 'system.package.buyInterfaceTitle', 'mdi:shopping-outline', 'menu', 'Package:Mall:Interface', 20, NULL, NULL, NULL, 1),
  (804, 800, 'UserPointPackage', '/purchase/point', '/purchase/point/list', NULL, 'system.package.buyPointTitle', 'mdi:database-plus', 'menu', 'Package:Mall:Point', 30, NULL, NULL, NULL, 1),
  (803, 800, 'UserInterfacePackageDetail', '/purchase/interface/detail/:id', '/purchase/interface/detail', NULL, 'system.package.interfacePackageBuy', 'mdi:receipt-text-outline', 'menu', 'Package:Mall:InterfaceDetail', 40, NULL, NULL, 1, 1),
  (805, 0, 'UserPaymentOrder', '/purchase/orders', '/payment/order/user', NULL, '交易记录', 'mdi:receipt-clock-outline', 'menu', 'Payment:Order:User', 60, NULL, NULL, NULL, 1),
  (900, 0, 'PaymentManagement', '/payment', NULL, '/payment/config', 'system.payment.title', 'mdi:credit-card-settings-outline', 'catalog', NULL, 50, NULL, NULL, NULL, 1),
  (901, 900, 'PaymentConfig', '/payment/config', '/payment/config/index', NULL, 'system.payment.configTitle', 'mdi:credit-card-settings-outline', 'menu', 'Payment:Config:Alipay', 10, NULL, NULL, NULL, 1),
  (905, 900, 'PaymentAmountConfig', '/payment/amounts', '/payment/amount/index', NULL, 'system.payment.amountTitle', 'mdi:cash-plus', 'menu', 'Payment:Amount:List', 20, NULL, NULL, NULL, 1),
  (904, 900, 'PaymentOrder', '/payment/orders', '/payment/order/index', NULL, '支付订单', 'mdi:receipt-text-clock-outline', 'menu', 'Payment:Order:List', 30, NULL, NULL, NULL, 1),
  (902, 901, 'PaymentConfigSave', '/payment/config/save', NULL, NULL, 'common.save', NULL, 'button', 'Payment:Config:Save', 11, NULL, NULL, 1, 1),
  (903, 901, 'PaymentConfigValidate', '/payment/config/validate', NULL, NULL, 'system.payment.validate', NULL, 'button', 'Payment:Config:Validate', 12, NULL, NULL, 1, 1),
  (906, 905, 'PaymentAmountCreate', '/payment/amounts/create', NULL, NULL, 'common.create', NULL, 'button', 'Payment:Amount:Create', 21, NULL, NULL, 1, 1),
  (907, 905, 'PaymentAmountEdit', '/payment/amounts/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Payment:Amount:Edit', 22, NULL, NULL, 1, 1),
  (908, 905, 'PaymentAmountDelete', '/payment/amounts/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Payment:Amount:Delete', 23, NULL, NULL, 1, 1),
  (909, 905, 'PaymentAmountStatus', '/payment/amounts/status', NULL, NULL, '状态', NULL, 'button', 'Payment:Amount:Status', 24, NULL, NULL, 1, 1),
  (1000, 0, 'NoticeManagement', '/notice-admin', NULL, '/notice-admin/list', '公告管理', 'mdi:bullhorn-outline', 'catalog', NULL, 60, NULL, NULL, NULL, 1),
  (1001, 1000, 'NoticeAdminList', '/notice-admin/list', '/notice/admin/list', NULL, '公告列表', 'mdi:clipboard-text-outline', 'menu', 'Notice:Admin:List', 10, NULL, NULL, NULL, 1),
  (1002, 1001, 'NoticeCreate', '/notice-admin/list/create', NULL, NULL, 'common.create', NULL, 'button', 'Notice:Admin:Create', 11, NULL, NULL, 1, 1),
  (1003, 1001, 'NoticeEdit', '/notice-admin/list/edit', NULL, NULL, 'common.edit', NULL, 'button', 'Notice:Admin:Edit', 12, NULL, NULL, 1, 1),
  (1004, 1001, 'NoticeDelete', '/notice-admin/list/delete', NULL, NULL, 'common.delete', NULL, 'button', 'Notice:Admin:Delete', 13, NULL, NULL, 1, 1),
  (1005, 1001, 'NoticeStatus', '/notice-admin/list/status', NULL, NULL, '状态', NULL, 'button', 'Notice:Admin:Status', 14, NULL, NULL, 1, 1),
  (1006, 1001, 'NoticeTop', '/notice-admin/list/top', NULL, NULL, '置顶', NULL, 'button', 'Notice:Admin:Top', 15, NULL, NULL, 1, 1),
  (1008, 1001, 'NoticePopup', '/notice-admin/list/popup', NULL, NULL, '每日弹窗', NULL, 'button', 'Notice:Admin:Popup', 16, NULL, NULL, 1, 1),
  (1007, 0, 'UserNoticeCenter', '/notice', '/notice/user/list', NULL, '公告列表', 'mdi:bullhorn-outline', 'menu', 'Notice:User:List', 70, NULL, NULL, NULL, 1),
  (400, 0, 'UserManagement', '/user', NULL, '/user/list', 'system.user.title', 'mdi:account-cog', 'catalog', NULL, 20, NULL, NULL, NULL, 1),
  (401, 400, 'SystemUser', '/user/list', '/system/user/list', NULL, 'system.user.listTitle', 'mdi:account', 'menu', 'System:User:List', 10, NULL, NULL, NULL, 1),
  (402, 401, 'SystemUserCreate', '/user/list/create', NULL, NULL, 'common.create', NULL, 'button', 'System:User:Create', 11, NULL, NULL, 1, 1),
  (403, 401, 'SystemUserEdit', '/user/list/edit', NULL, NULL, 'common.edit', NULL, 'button', 'System:User:Edit', 12, NULL, NULL, 1, 1),
  (404, 401, 'SystemUserDelete', '/user/list/delete', NULL, NULL, 'common.delete', NULL, 'button', 'System:User:Delete', 13, NULL, NULL, 1, 1),
  (201, 400, 'SystemRole', '/user/role', '/system/role/list', NULL, 'system.role.title', 'mdi:account-group', 'menu', 'System:Role:List', 20, NULL, NULL, NULL, 1),
  (200, 0, 'System', '/system', NULL, NULL, 'system.title', 'ion:settings-outline', 'catalog', NULL, 90, NULL, NULL, NULL, 1),
  (202, 200, 'SystemMenu', '/system/menu', '/system/menu/list', NULL, 'system.menu.title', 'mdi:menu', 'menu', 'System:Menu:List', 10, NULL, NULL, NULL, 1),
  (210, 200, 'SystemRegister', '/system/register', '/system/register/index', NULL, 'system.register.title', 'mdi:email-edit-outline', 'menu', 'System:Register:Config', 20, NULL, NULL, NULL, 0),
  (211, 200, 'SystemSiteConfig', '/system/site', '/system/site/index', NULL, '站点配置', 'mdi:web-sync', 'menu', 'System:Site:Config', 30, NULL, NULL, NULL, 1),
  (250, 0, 'RegisterManage', '/register', NULL, NULL, '注册管理', 'mdi:account-cog-outline', 'catalog', NULL, 70, NULL, NULL, NULL, 1),
  (251, 250, 'RegisterBasicConfig', '/register/basic', '/system/register/index', NULL, '基础配置', 'mdi:cog-outline', 'menu', 'System:Register:Config', 10, NULL, NULL, NULL, 1),
  (252, 250, 'RegisterEmailConfig', '/register/email', '/system/register/email-config', NULL, '邮箱配置', 'mdi:email-outline', 'menu', 'System:Register:Email', 20, NULL, NULL, NULL, 1),
  (253, 250, 'RegisterMobileConfig', '/register/mobile', '/system/register/mobile-config', NULL, '手机号配置', 'mdi:cellphone-text', 'menu', 'System:Register:Mobile', 30, NULL, NULL, NULL, 1),
  (1009, 1000, 'NoticeHomeNotice', '/notice-admin/home-notice', '/notice/admin/home-notice/index', NULL, '首页公告配置', 'mdi:bullhorn-variant-outline', 'menu', 'Notice:Admin:HomeConfig', 20, NULL, NULL, NULL, 1),
  (1100, 0, 'FriendLinkManagement', '/friend-link', NULL, '/friend-link/list', '友链管理', 'mdi:link-variant', 'catalog', NULL, 80, NULL, NULL, NULL, 1),
  (1101, 1100, 'FriendLinkList', '/friend-link/list', '/friend-link/list', NULL, '友链列表', 'mdi:link-box-variant-outline', 'menu', 'FriendLink:List', 10, NULL, NULL, NULL, 1),
  (1102, 1101, 'FriendLinkCreate', '/friend-link/list/create', NULL, NULL, 'common.create', NULL, 'button', 'FriendLink:Create', 11, NULL, NULL, 1, 1),
  (1103, 1101, 'FriendLinkEdit', '/friend-link/list/edit', NULL, NULL, 'common.edit', NULL, 'button', 'FriendLink:Edit', 12, NULL, NULL, 1, 1),
  (1104, 1101, 'FriendLinkDelete', '/friend-link/list/delete', NULL, NULL, 'common.delete', NULL, 'button', 'FriendLink:Delete', 13, NULL, NULL, 1, 1),
  (1105, 1101, 'FriendLinkStatus', '/friend-link/list/status', NULL, NULL, '状态', NULL, 'button', 'FriendLink:Status', 14, NULL, NULL, 1, 1),
  (1110, 1100, 'FriendLinkApplication', '/friend-link/applications', '/friend-link/applications', NULL, '友链申请', 'mdi:link-plus', 'menu', 'FriendLink:Application:List', 20, NULL, NULL, NULL, 1),
  (1111, 1110, 'FriendLinkApplicationApprove', '/friend-link/applications/approve', NULL, NULL, '通过', NULL, 'button', 'FriendLink:Application:Approve', 21, NULL, NULL, 1, 1),
  (1112, 1110, 'FriendLinkApplicationReject', '/friend-link/applications/reject', NULL, NULL, '驳回', NULL, 'button', 'FriendLink:Application:Reject', 22, NULL, NULL, 1, 1),
  (1113, 1110, 'FriendLinkApplicationConfig', '/friend-link/applications/config', NULL, NULL, '申请开关', NULL, 'button', 'FriendLink:Application:Config', 23, NULL, NULL, 1, 1),
  (204, 201, 'SystemRoleCreate', '/user/role/create', NULL, NULL, 'common.create', NULL, 'button', 'System:Role:Create', 21, NULL, NULL, 1, 1),
  (205, 201, 'SystemRoleEdit', '/user/role/edit', NULL, NULL, 'common.edit', NULL, 'button', 'System:Role:Edit', 22, NULL, NULL, 1, 1),
  (206, 201, 'SystemRoleDelete', '/user/role/delete', NULL, NULL, 'common.delete', NULL, 'button', 'System:Role:Delete', 23, NULL, NULL, 1, 1),
  (207, 202, 'SystemMenuCreate', '/system/menu/create', NULL, NULL, 'common.create', NULL, 'button', 'System:Menu:Create', 11, NULL, NULL, 1, 1),
  (208, 202, 'SystemMenuEdit', '/system/menu/edit', NULL, NULL, 'common.edit', NULL, 'button', 'System:Menu:Edit', 12, NULL, NULL, 1, 1),
  (209, 202, 'SystemMenuDelete', '/system/menu/delete', NULL, NULL, 'common.delete', NULL, 'button', 'System:Menu:Delete', 13, NULL, NULL, 1, 1),
--   (300, 0, 'About', '/about', '/_core/about/index', NULL, 'demos.vben.about', 'lucide:copyright', 'menu', NULL, 100, NULL, NULL, NULL, 1),
  (301, 0, 'Profile', '/profile', '/_core/profile/index', NULL, 'page.auth.profile', 'lucide:user', 'menu', NULL, 9998, NULL, NULL, 1, 1);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, `id` FROM `sys_menu` WHERE `status` = 1 AND `id` NOT IN (506, 509, 600, 601, 602, 603, 604, 605, 800, 801, 802, 803, 804, 805, 1007);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 2, `id` FROM `sys_menu` WHERE `id` IN (102, 301, 505, 506, 509, 600, 601, 602, 603, 604, 605, 800, 801, 802, 803, 804, 805, 1007);
