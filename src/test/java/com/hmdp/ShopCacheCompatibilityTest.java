package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopCacheCompatibilityTest {
    static class Service extends ShopServiceImpl {
        int reads;
        Shop database;
        @Override public Shop getById(Serializable id) { reads++; return database; }
    }
    @Test void repairsLogicalWrapperDecodedAsEmptyShop() {
        Service service = new Service();
        Shop actual = new Shop(); actual.setId(1L); actual.setName("test"); service.database=actual;
        CacheClient cache = mock(CacheClient.class);
        ReflectionTestUtils.setField(service,"cacheClient",cache);
        when(cache.queryWithPassThrough(eq(CACHE_SHOP_KEY),eq(1L),eq(Shop.class),any(),eq(CACHE_SHOP_TTL),eq(TimeUnit.MINUTES)))
                .thenReturn(new Shop());
        Result result = service.queryById(1L);
        assertTrue(result.getSuccess()); assertEquals(actual,result.getData()); assertEquals(1,service.reads);
        verify(cache).set(CACHE_SHOP_KEY+1,actual,CACHE_SHOP_TTL,TimeUnit.MINUTES);
    }
    @Test void validCachedShopDoesNotQueryDatabase() {
        Service service = new Service(); Shop cached = new Shop(); cached.setId(1L);
        CacheClient cache = mock(CacheClient.class); ReflectionTestUtils.setField(service,"cacheClient",cache);
        when(cache.queryWithPassThrough(eq(CACHE_SHOP_KEY),eq(1L),eq(Shop.class),any(),eq(CACHE_SHOP_TTL),eq(TimeUnit.MINUTES)))
                .thenReturn(cached);
        assertEquals(cached,service.queryById(1L).getData()); assertEquals(0,service.reads);
    }
}
