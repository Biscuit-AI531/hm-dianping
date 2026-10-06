package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IShopService shopService;

    @Value("${citylens.catalog-admin-ids:}")
    private String catalogAdminIds;

    private boolean canManage() {
        return UserHolder.getUser() != null && Arrays.stream(catalogAdminIds.split(","))
                .map(String::trim).anyMatch(id -> id.equals(UserHolder.getUser().getId().toString()));
    }

    @GetMapping("/manage/capabilities")
    public Result capabilities() {
        Map<String, Object> data = new HashMap<>();
        data.put("can_create_voucher", canManage());
        data.put("server_time", LocalDateTime.now().toString());
        data.put("server_zone", ZoneId.systemDefault().toString());
        return Result.ok(data);
    }

    private String validate(Voucher v, boolean seckill) {
        if (!canManage()) {
            return "当前账号没有演示商品管理权限";
        }
        if (v.getId() != null || v.getShopId() == null || v.getShopId() <= 0
                || shopService.getById(v.getShopId()) == null) return "关联店铺不存在或参数无效";
        if (v.getTitle() == null || v.getTitle().trim().isEmpty() || v.getTitle().length() > 100
                || v.getRules() == null || v.getRules().trim().isEmpty() || v.getRules().length() > 1000
                || (v.getSubTitle() != null && v.getSubTitle().length() > 200)) return "标题或规则无效";
        if (v.getPayValue() == null || v.getActualValue() == null || v.getPayValue() < 0
                || v.getActualValue() <= 0 || v.getActualValue() > 10000000
                || v.getPayValue() > v.getActualValue()) return "券金额无效";
        if (seckill && (v.getStock() == null || v.getStock() <= 0 || v.getStock() > 100000
                || v.getBeginTime() == null || v.getEndTime() == null
                || !v.getEndTime().isAfter(v.getBeginTime()))) return "秒杀库存或时间无效";
        v.setType(seckill ? 1 : 0);
        v.setStatus(1);
        v.setCreateTime(null);
        v.setUpdateTime(null);
        if (!v.getTitle().startsWith("【演示】")) v.setTitle("【演示】" + v.getTitle().trim());
        return v.getTitle().length() > 100 ? "标题过长" : null;
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        String error = validate(voucher, true);
        if (error != null) return Result.fail(error);
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        String error = validate(voucher, false);
        if (error != null) return Result.fail(error);
        if (!voucherService.save(voucher)) return Result.fail("保存失败");
        return Result.ok(voucher.getId());
    }


    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
