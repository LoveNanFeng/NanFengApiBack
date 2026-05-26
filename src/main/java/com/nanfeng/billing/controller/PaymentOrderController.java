package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.PaymentOrderExpirationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping
public class PaymentOrderController {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentOrderExpirationService paymentOrderExpirationService;

    @GetMapping("/system/payment/orders/list")
    public ApiResponse<PageResult<Map<String, Object>>> adminOrders(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String orderType,
        @RequestParam(required = false) String payChannel,
        @RequestParam(required = false) String status
    ) {
        if (!SecurityUtils.currentUser().roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "仅管理员可操作");
        }
        return ApiResponse.ok(orderPage(page, pageSize, keyword, orderType, payChannel, status, null));
    }

    @GetMapping("/payment/orders/list")
    public ApiResponse<PageResult<Map<String, Object>>> myOrders(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String orderType,
        @RequestParam(required = false) String payChannel,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(orderPage(
            page,
            pageSize,
            keyword,
            orderType,
            payChannel,
            status,
            SecurityUtils.currentUser().id()
        ));
    }

    private PageResult<Map<String, Object>> orderPage(
        int page,
        int pageSize,
        String keyword,
        String orderType,
        String payChannel,
        String status,
        Long userId
    ) {
        paymentOrderExpirationService.closeExpiredPendingOrders();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();

        if (userId != null) {
            where.append(" and o.user_id = ?\n");
            args.add(userId);
        }
        appendEqual(where, args, "o.order_type", orderType);
        appendEqual(where, args, "o.pay_channel", payChannel);
        appendEqual(where, args, "o.status", status);
        appendKeyword(where, args, keyword);

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_payment_recharge_order o
            inner join sys_user u on u.id = o.user_id
            """ + where, Long.class, args.toArray());

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select o.id,
                   o.order_no as orderNo,
                   o.order_type as orderType,
                   o.user_id as userId,
                   u.username,
                   u.real_name as realName,
                   o.biz_id as bizId,
                   o.biz_name as bizName,
                   o.amount,
                   o.gift_amount as giftAmount,
                   o.pay_channel as payChannel,
                   o.pay_product as payProduct,
                   o.alipay_method as alipayMethod,
                   o.status,
                   o.subject,
                   o.body,
                   o.trade_no as tradeNo,
                   o.buyer_id as buyerId,
                   o.client_type as clientType,
                   date_format(o.paid_time, '%Y-%m-%d %H:%i:%s') as paidTime,
                   date_format(o.expire_time, '%Y-%m-%d %H:%i:%s') as expireTime,
                   date_format(o.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_payment_recharge_order o
            inner join sys_user u on u.id = o.user_id
            """ + where + """
             order by o.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    private void appendEqual(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return;
        }
        where.append(" and ").append(column).append(" = ?\n");
        args.add(value.trim().toUpperCase());
    }

    private void appendKeyword(StringBuilder where, List<Object> args, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String likeValue = "%" + keyword.trim() + "%";
        where.append("""
             and (
                 o.order_no like ?
                 or o.subject like ?
                 or o.body like ?
                 or o.biz_name like ?
                 or o.trade_no like ?
                 or u.username like ?
                 or u.real_name like ?
             )
            """);
        for (int i = 0; i < 7; i++) {
            args.add(likeValue);
        }
    }
}
