package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private static final String ORDER_STREAM = "stream.orders";
    private static final String ORDER_GROUP = "g1";

    @PostConstruct
    private void init() {
        // 首次启动时创建 Stream 和消费组；重启时保留已有待处理消息。
        stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
            try {
                connection.execute("XGROUP",
                        "CREATE".getBytes(StandardCharsets.UTF_8),
                        ORDER_STREAM.getBytes(StandardCharsets.UTF_8),
                        ORDER_GROUP.getBytes(StandardCharsets.UTF_8),
                        "0".getBytes(StandardCharsets.UTF_8),
                        "MKSTREAM".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                    throw e;
                }
            }
            return null;
        });
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void stopConsumer() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    handlePendingList();
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    if (!running) break;
                    log.error("处理订单异常", e);
                    try { Thread.sleep(200); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        private void handlePendingList() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_STREAM, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    throw new IllegalStateException("待处理订单尚未落库，保留消息重试", e);
                }
            }
        }
    }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    createVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    protected void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if (!lock.tryLock()) throw new IllegalStateException("订单锁忙，保留消息重试");
        try {
            transactionTemplate.execute(status -> {
                VoucherOrder existing = getById(voucherOrder.getId());
                if (existing != null) {
                    if (!userId.equals(existing.getUserId()) || !voucherId.equals(existing.getVoucherId()))
                        throw new IllegalStateException("订单号冲突");
                    return null;
                }
                if (query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0)
                    throw new IllegalStateException("资格对应的订单号不一致，保留消息核对");
                boolean deducted = seckillVoucherService.update().setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId).gt("stock", 0).update();
                if (!deducted) throw new IllegalStateException("库存不足，保留消息核对");
                Voucher voucher = voucherService.getById(voucherId);
                voucherOrder.setStatus(voucher != null && voucher.getPayValue() != null
                        && voucher.getPayValue() == 0L ? 2 : 1);
                if (!save(voucherOrder)) throw new IllegalStateException("订单写入失败，回滚库存");
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || voucher.getStatus() == null || voucher.getStatus() != 1
                || voucher.getType() == null || voucher.getType() != 1) {
            return Result.fail("秒杀券不存在或已下架");
        }
        com.hmdp.entity.SeckillVoucher sale = seckillVoucherService.getById(voucherId);
        LocalDateTime now = LocalDateTime.now();
        if (sale == null || sale.getBeginTime() == null || sale.getEndTime() == null
                || now.isBefore(sale.getBeginTime()) || now.isAfter(sale.getEndTime())) {
            return Result.fail("秒杀券不在可购买时段");
        }
        // 演示 SQL 初始化的券没有经过新增券接口；仅在 Redis 缺键时补齐库存。
        stringRedisTemplate.opsForValue().setIfAbsent("seckill:stock:" + voucherId,
                String.valueOf(sale.getStock()));
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        if (result == null) {
            return Result.fail("订单服务暂时不可用");
        }
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }

    @Override
    public Result orderOrdinaryVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:ordinary:" + userId);
        if (!lock.tryLock()) {
            return Result.fail("订单正在处理，请稍后查询");
        }
        try {
            Voucher voucher = voucherService.getById(voucherId);
            if (voucher == null || voucher.getStatus() == null || voucher.getStatus() != 1
                    || voucher.getType() == null || voucher.getType() != 0) {
                return Result.fail("普通券不存在或已下架");
            }
            VoucherOrder existing = query().eq("user_id", userId).eq("voucher_id", voucherId)
                    .ne("status", 4)
                    .orderByDesc("create_time").last("LIMIT 1").one();
            if (existing != null) {
                return Result.ok(existing.getId());
            }
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            // 免费券直接领取；付费券只创建待支付订单，不能冒充支付成功。
            order.setStatus(voucher.getPayValue() != null && voucher.getPayValue() == 0L ? 2 : 1);
            if (!save(order)) {
                return Result.fail("创建订单失败");
            }
            return Result.ok(order.getId());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result queryMyOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || !UserHolder.getUser().getId().equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        Map<String, Object> detail = BeanUtil.beanToMap(order);
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher != null) {
            detail.put("voucherType", voucher.getType());
            detail.put("payValue", voucher.getPayValue());
            detail.put("shopId", voucher.getShopId());
            detail.put("voucherTitle", voucher.getTitle());
        }
        return Result.ok(detail);
    }

    @Override
    public Result queryMyVoucherOrder(Long voucherId) {
        VoucherOrder order = query().eq("user_id", UserHolder.getUser().getId())
                .eq("voucher_id", voucherId).orderByDesc("create_time").last("LIMIT 1").one();
        return Result.ok(order);
    }

    @Override
    public Result queryMyOrders(Integer page, boolean heldOnly) {
        if (page == null || page < 1 || page > 10000) return Result.fail("分页参数无效");
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<VoucherOrder> query = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId());
        if (heldOnly) query.eq(VoucherOrder::getStatus, 2);
        Page<VoucherOrder> result = query.orderByDesc(VoucherOrder::getCreateTime)
                .orderByDesc(VoucherOrder::getId).page(new Page<>(page, 20));
        Map<Long, Voucher> vouchers = result.getRecords().isEmpty() ? Collections.emptyMap() :
                voucherService.listByIds(result.getRecords().stream().map(VoucherOrder::getVoucherId)
                        .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Voucher::getId, v -> v));
        List<Map<String, Object>> items = new ArrayList<>();
        for (VoucherOrder order : result.getRecords()) {
            Voucher voucher = vouchers.get(order.getVoucherId());
            Map<String, Object> item = new HashMap<>();
            item.put("order_id", order.getId().toString());
            item.put("voucher_id", order.getVoucherId());
            item.put("status", order.getStatus());
            item.put("created_at", order.getCreateTime());
            if (voucher != null) {
                item.put("shop_id", voucher.getShopId());
                item.put("title", voucher.getTitle());
                item.put("pay_cents", voucher.getPayValue());
                item.put("voucher_type", voucher.getType());
                item.put("rules", voucher.getRules());
            }
            items.add(item);
        }
        return Result.ok(items, result.getTotal());
    }

    @Override
    public Result cancelUnpaidOrdinaryOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher == null || voucher.getType() == null || voucher.getType() != 0
                || voucher.getPayValue() == null || voucher.getPayValue() <= 0) {
            return Result.fail("仅支持取消未支付的普通券订单");
        }
        if (order.getStatus() != null && order.getStatus() == 4) {
            return Result.ok(orderId);
        }
        if (order.getStatus() == null || order.getStatus() != 1) {
            return Result.fail("订单不是待支付状态");
        }
        boolean changed = update().set("status", 4).eq("id", orderId)
                .eq("user_id", userId).eq("status", 1).update();
        return changed ? Result.ok(orderId) : Result.fail("订单状态已变化，请刷新后重试");
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.返回订单id
        return Result.ok(orderId);
    }*/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        return createVoucherOrder(voucherId);
    }



    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if(!isLock){
            // 获取锁失败，直接返回失败或者重试
            return Result.fail("不允许重复下单！");
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        } finally {
            // 释放锁
            redisLock.unlock();
        }

    }*/
    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock(1200);
        // 判断
        if(!isLock){
            // 获取锁失败，直接返回失败或者重试
            return Result.fail("不允许重复下单！");
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        } finally {
            // 释放锁
            redisLock.unlock();
        }

    }*/

    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        synchronized (userId.toString().intern()) {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        }
    }*/
}
