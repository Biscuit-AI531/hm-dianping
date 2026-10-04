package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("ordinary/{id}")
    public Result orderOrdinaryVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.orderOrdinaryVoucher(voucherId);
    }

    @GetMapping("{id}")
    public Result queryMyOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryMyOrder(orderId);
    }

    @GetMapping("of/voucher/{id}")
    public Result queryMyVoucherOrder(@PathVariable("id") Long voucherId) {
        return voucherOrderService.queryMyVoucherOrder(voucherId);
    }

    @PostMapping("{id}/cancel")
    public Result cancelUnpaidOrdinaryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelUnpaidOrdinaryOrder(orderId);
    }
}
