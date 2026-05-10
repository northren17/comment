package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.CacheClient;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.geo.Circle;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    //创建一个 固定大小为 10 的线程池。
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据Id查询用户
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 返回
        return Result.ok(shop);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺为空");
        }
        // 写入数据库
        this.updateById(shop);
        //  删除缓存
        stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }

    @Override
    /*
        * 根据商铺类型分页查询商铺信息
     */
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
        // includeDistance,查询结果里包含距离 sortAscending,根据距离排序 limit end,分页查询前 end 个
        Object resultsObj = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(5000)),
                GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(end)
        );
        // 4.解析出id
        if (resultsObj == null) {
            return Result.ok(java.util.Collections.emptyList());
        }
        // 将结果转换为 Iterable，再收集到 List，方便分页判断
        Iterable<?> iterable = (Iterable<?>) resultsObj;
        java.util.List<Object> all = new java.util.ArrayList<>();
        //all::add 是一个方法引用，等价于 item -> all.add(item)，它将 iterable 中的每个元素添加到 all 列表中。
        iterable.forEach(all::add);
        if (all.size() <= from) {
            return Result.ok(java.util.Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new java.util.ArrayList<>(all.size());
        java.util.Map<String, Distance> distanceMap = new java.util.HashMap<>(all.size());
//stream() 是 Java 8 引入的一个功能强大的工具，它允许我们以声明式的方式处理集合数据。skip(from) 方法会跳过前 from 个元素，forEach(item -> {...}) 则对剩余的每个元素执行给定的操作。
        all.stream().skip(from).forEach(item -> {
            GeoResult<?> geoResult = (GeoResult<?>) item;
//cotent是GeoResult 类中的一个方法，用于获取地理位置的内容。在这个上下文中，content 返回的是一个 GeoLocation 对象，包含了商铺的位置信息和名称（通常是商铺的 ID）。
            GeoLocation<?> location = (GeoLocation<?>) geoResult.getContent();
//(String) location.getName()强转容易空指针异常，所以先转为String，再转为Long
            String shopIdStr = String.valueOf(location.getName());
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = geoResult.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        /*
           SELECT *
           FROM tb_shop
           WHERE id IN (5,1,8)
           ORDER BY FIELD(id,5,1,8)
         */
        java.util.List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (int i = 0; i < shops.size(); i++) {
            Shop s = shops.get(i);
            String shopId = s.getId().toString();
            // 重新设置距离，先判空以防 NPE
            Distance d = distanceMap.get(shopId);
            if (d != null) {
                s.setDistance(d.getValue());
            } else {
                s.setDistance(0.0);
            }
        }
        return Result.ok(shops);
    }
}
