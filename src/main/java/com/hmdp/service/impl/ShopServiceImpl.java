package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    @Override
    public Result searchShops(ShopSearchDTO criteria) {
        if (criteria.getTypeId() == null || criteria.getTypeId() <= 0) {
            return Result.fail("typeId 必须是正整数");
        }
        Integer page = criteria.getPage();
        Integer size = criteria.getSize();
        if (page == null || page < 1 || page > 100000 || size == null || size < 1 || size > 20) {
            return Result.fail("page 必须为 1~100000，size 必须为 1~20");
        }
        if (criteria.getMaxPrice() != null && criteria.getMaxPrice() < 0) {
            return Result.fail("maxPrice 不能为负数");
        }
        if (criteria.getMinScore() != null && (criteria.getMinScore() < 0 || criteria.getMinScore() > 50)) {
            return Result.fail("minScore 必须为 0~50");
        }
        Double x = criteria.getX();
        Double y = criteria.getY();
        if ((x == null) != (y == null)) {
            return Result.fail("x 和 y 必须一起提供");
        }
        if (x != null && (!Double.isFinite(x) || !Double.isFinite(y) || x < -180 || x > 180 || y < -90 || y > 90)) {
            return Result.fail("坐标超出范围");
        }
        String sort = StrUtil.blankToDefault(criteria.getSort(), "score");
        if (!"score".equals(sort) && !"price".equals(sort) && !"distance".equals(sort)) {
            return Result.fail("sort 只支持 score、price、distance");
        }
        if ("distance".equals(sort) && x == null) {
            return Result.fail("按距离排序需要 x 和 y");
        }

        QueryWrapper<Shop> query = new QueryWrapper<>();
        query.eq("type_id", criteria.getTypeId());
        if (StrUtil.isNotBlank(criteria.getKeyword())) {
            query.like("name", criteria.getKeyword().trim());
        }
        if (StrUtil.isNotBlank(criteria.getArea())) {
            String area = criteria.getArea().trim();
            query.and(q -> q.like("area", area).or().like("address", area));
        }
        if (criteria.getMaxPrice() != null) {
            query.le("avg_price", criteria.getMaxPrice());
        }
        if (criteria.getMinScore() != null) {
            query.ge("score", criteria.getMinScore());
        }
        if (Boolean.TRUE.equals(criteria.getHasVoucher())) {
            query.apply("EXISTS (SELECT 1 FROM tb_voucher v "
                    + "LEFT JOIN tb_seckill_voucher sv ON sv.voucher_id = v.id "
                    + "WHERE v.shop_id = tb_shop.id AND v.status = 1 "
                    + "AND (v.type = 0 OR (v.type = 1 AND sv.stock > 0 "
                    + "AND sv.begin_time <= NOW() AND sv.end_time >= NOW())))");
        }

        if (x == null) {
            if ("price".equals(sort)) {
                query.orderByAsc("avg_price").orderByDesc("score").orderByAsc("id");
            } else {
                query.orderByDesc("score").orderByAsc("avg_price").orderByAsc("id");
            }
            Page<Shop> resultPage = page(new Page<>(page, size), query);
            return Result.ok(resultPage.getRecords(), resultPage.getTotal());
        }

        // 先用经纬度范围缩小 MySQL 候选集，再用球面距离精确筛选 5 公里范围。
        final double radiusMeters = 5000.0;
        double latDelta = radiusMeters / 111320.0;
        double lonDelta = radiusMeters / (111320.0 * Math.max(0.01, Math.cos(Math.toRadians(y))));
        query.between("x", x - lonDelta, x + lonDelta)
                .between("y", y - latDelta, y + latDelta);
        List<Shop> candidates = list(query);
        List<Shop> nearby = new ArrayList<>();
        for (Shop shop : candidates) {
            if (shop.getX() == null || shop.getY() == null) {
                continue;
            }
            double distance = distanceMeters(x, y, shop.getX(), shop.getY());
            if (distance <= radiusMeters) {
                shop.setDistance(distance);
                nearby.add(shop);
            }
        }
        nearby.sort((left, right) -> {
            int byPrimary;
            if ("distance".equals(sort)) {
                byPrimary = Double.compare(left.getDistance(), right.getDistance());
            } else if ("price".equals(sort)) {
                byPrimary = Comparator.nullsLast(Long::compareTo).compare(left.getAvgPrice(), right.getAvgPrice());
            } else {
                byPrimary = Comparator.nullsLast(Comparator.<Integer>reverseOrder()).compare(left.getScore(), right.getScore());
            }
            return byPrimary != 0 ? byPrimary : Long.compare(left.getId(), right.getId());
        });
        long start = (long) (page - 1) * size;
        if (start >= nearby.size()) {
            return Result.ok(Collections.emptyList(), (long) nearby.size());
        }
        int from = (int) start;
        int to = Math.min(from + size, nearby.size());
        return Result.ok(nearby.subList(from, to), (long) nearby.size());
    }

    private static double distanceMeters(double x1, double y1, double x2, double y2) {
        double lat1 = Math.toRadians(y1);
        double lat2 = Math.toRadians(y2);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(x2 - x1);
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
