package com.hmdp;

import com.hmdp.controller.UserController;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLogoutTest {
    @Test
    void logoutRevokesOnlyPresentedToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", redis);

        Result missing = controller.logout(null);
        assertFalse(missing.getSuccess());
        verifyNoInteractions(redis);

        Result result = controller.logout("test-token");
        assertTrue(result.getSuccess());
        verify(redis).delete("login:token:test-token");
        verifyNoMoreInteractions(redis);
    }
}
