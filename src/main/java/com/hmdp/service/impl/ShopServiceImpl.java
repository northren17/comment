package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.CacheClient;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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
     * @param id
     * @return
     */
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
    /*
    缓存穿透解决方案：大量无效信息访问数据库
     *//*
*//*
互斥锁缓存击穿解决：热点key突然失效，大量用户访问db
 *//*
    *//*
    获取释放互斥锁
     *//*
private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    *//*
    互斥查询
     *//*
public Shop queryWithMutex(Long id)  {
    String key = CACHE_SHOP_KEY + id;
    // 1、从redis中查询商铺缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    // 2、判断是否存在
    if (StrUtil.isNotBlank(shopJson)) {//必须有数据，空串也排除
        // 存在,直接返回
        return JSONUtil.toBean(shopJson, Shop.class);
    }
    //判断命中的值是否是空值
    if (shopJson != null) {            //区分“有数据 / 空数据 “”：查过数据库/ 没查过 为null”三种状态
        //返回一个错误信息
        return null;
    }
    // 4.实现缓存重构
    //4.1 获取互斥锁
    String lockKey = "lock:shop:" + id;
    Shop shop = null;
    try {
        boolean isLock = tryLock(lockKey);
        // 4.2 判断否获取成功
        if(!isLock){
            //4.3 失败，则休眠重试
            Thread.sleep(50);
            return queryWithMutex(id);
        }
        //4.4 成功，根据id查询数据库
        shop = getById(id);
        // 5.不存在，返回错误
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);

    }catch (Exception e){
        throw new RuntimeException(e);
    }
    finally {
        //7.释放互斥锁
        unlock(lockKey);
    }
    return shop;
}
*//*
逻辑过期时间
 *//*
public Shop queryWithLogicalExpire( Long id ){
    String key = CACHE_SHOP_KEY + id;
    //1.从redis中查询
    String shopJson = stringRedisTemplate.opsForValue().get(key);
  // 2.未命中则返回null，前提redis预热
if (StrUtil.isBlank(shopJson)) {
        return null;
        *//*   json == null
           json == ""
           json == " "
        都算“没有缓存”  *//*
    }
//3.命中判断是否过期
    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
   Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
    LocalDateTime expireTime= redisData.getExpireTime();
    //没过期直接返回
    if (expireTime.isAfter(LocalDateTime.now())) {
        return shop;
    }
    //过期了获取互斥锁，成功返回过期数据，失败返回过期数据
    //4.获取锁
    String lockKey = LOCK_SHOP_KEY + id;
    boolean isLock = tryLock(lockKey);
    //5.获取失败返过期
    if(!isLock){
      return shop;
    }
//6.成功获取互斥锁
    CACHE_REBUILD_EXECUTOR.submit(()->{
        try {
            //重建缓存
            this.saveShop2Redis(id, 20L);

        }
        catch (Exception e){
         throw new RuntimeException(e);
            }
        finally {
            unlock(lockKey);} } );
       return shop;
}
    private void saveShop2Redis(Long id, long expireSeconds) {

        // 1. 查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }

        // 2. 构建逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. 转 JSON
        String json = JSONUtil.toJsonStr(redisData);

        // 4. 写入 Redis（不使用 Redis TTL，只用逻辑过期）
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + id,
                json
        );
    }*/
/*
更新店铺
 */
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
        stringRedisTemplate.delete("cache:shop:"+id);
        return Result.ok();
    }
}
