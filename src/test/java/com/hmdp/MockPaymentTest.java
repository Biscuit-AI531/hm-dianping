package com.hmdp;
import com.hmdp.entity.*;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.Serializable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MockPaymentTest {
    static class Service extends VoucherOrderServiceImpl {
        VoucherOrder order = new VoucherOrder().setId(88L).setUserId(7L).setVoucherId(2001L).setStatus(1);
        UpdateChainWrapper<VoucherOrder> chain = mock(UpdateChainWrapper.class, RETURNS_SELF);
        @Override public VoucherOrder getById(Serializable id) { return order; }
        @Override public UpdateChainWrapper<VoucherOrder> update() { return chain; }
    }
    @Test void validatesOwnerAmountStateAndIdempotence() {
        Service s=new Service(); UserDTO user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);
        IVoucherService vouchers=mock(IVoucherService.class);
        when(vouchers.getById(2001L)).thenReturn(new Voucher().setId(2001L).setPayValue(8900L));
        ReflectionTestUtils.setField(s,"voucherService",vouchers);
        try {
            assertFalse(s.payMockOrder(88L,8900L).getSuccess());
            ReflectionTestUtils.setField(s,"mockPaymentEnabled",true);
            assertFalse(s.payMockOrder(88L,1L).getSuccess());
            s.order.setUserId(8L);assertFalse(s.payMockOrder(88L,8900L).getSuccess());s.order.setUserId(7L);
            s.order.setStatus(4);assertFalse(s.payMockOrder(88L,8900L).getSuccess());s.order.setStatus(1);
            when(s.chain.update()).thenReturn(true);
            assertTrue(s.payMockOrder(88L,8900L).getSuccess());
            verify(s.chain).eq("user_id",7L);verify(s.chain).eq("status",1);
            clearInvocations(s.chain);s.order.setStatus(2);
            assertTrue(s.payMockOrder(88L,8900L).getSuccess());verifyNoInteractions(s.chain);
        } finally {UserHolder.removeUser();}
    }
}
