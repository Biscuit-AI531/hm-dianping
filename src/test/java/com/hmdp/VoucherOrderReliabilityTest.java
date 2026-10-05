package com.hmdp;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.Serializable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderReliabilityTest {
    static class Service extends VoucherOrderServiceImpl {
        VoucherOrder existing;
        boolean saveResult = true;
        @SuppressWarnings("unchecked")
        QueryChainWrapper<VoucherOrder> candidates = mock(QueryChainWrapper.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<VoucherOrder> personal = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        @Override public VoucherOrder getById(Serializable id) { return existing; }
        @Override public QueryChainWrapper<VoucherOrder> query() { return candidates; }
        @Override public LambdaQueryChainWrapper<VoucherOrder> lambdaQuery() { return personal; }
        @Override public boolean save(VoucherOrder order) { return saveResult; }
        void consume(VoucherOrder order) { createVoucherOrder(order); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistenceFailureRollsBackAndDuplicateDeliveryDoesNotDeductAgain() {
        Service service = new Service();
        RLock lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        ISeckillVoucherService stock = mock(ISeckillVoucherService.class);
        UpdateChainWrapper<SeckillVoucher> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        when(stock.update()).thenReturn(update);
        when(update.update()).thenReturn(true);
        IVoucherService vouchers = mock(IVoucherService.class);
        when(vouchers.getById(9L)).thenReturn(new Voucher().setId(9L).setPayValue(0L));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        ReflectionTestUtils.setField(service, "redissonClient", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stock);
        ReflectionTestUtils.setField(service, "voucherService", vouchers);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(manager));
        VoucherOrder order = new VoucherOrder().setId(88L).setUserId(7L).setVoucherId(9L);
        service.saveResult = false;
        assertThrows(IllegalStateException.class, () -> service.consume(order));
        verify(manager).rollback(status);
        verify(lock).unlock();
        reset(stock, manager);
        when(manager.getTransaction(any())).thenReturn(status);
        service.existing = order;
        service.consume(order);
        verifyNoInteractions(stock);
        verify(manager).commit(status);
    }

    @Test
    void lockContentionFailsConsumptionInsteadOfAcknowledgingSuccess() {
        Service service = new Service();
        RLock lock = mock(RLock.class);
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        ReflectionTestUtils.setField(service, "redissonClient", redis);
        assertThrows(IllegalStateException.class, () -> service.consume(
                new VoucherOrder().setId(88L).setUserId(7L).setVoucherId(9L)));
        verify(lock, never()).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void personalReadsAlwaysUseAuthenticatedOwnerAndHeldStatus() {
        Service service = new Service();
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(service.personal.page(any(Page.class))).thenReturn(new Page<VoucherOrder>(1, 20, 0));
        try {
            assertTrue(service.queryMyOrders(1, true).getSuccess());
            verify(service.personal).eq(any(), eq(7L));
            verify(service.personal).eq(any(), eq(2));
            assertFalse(service.queryMyOrders(0, false).getSuccess());
        } finally {
            UserHolder.removeUser();
        }
    }
}
