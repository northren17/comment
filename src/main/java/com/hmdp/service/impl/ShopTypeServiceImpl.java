package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType>
        implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String CACHE_KEY = "cache:shoptype:list";

    @Override
    public Result queryTypeList() {

        // 1. 查 Redis
        String json = stringRedisTemplate.opsForValue().get(CACHE_KEY);

        // 2. 缓存命中
        if (StrUtil.isNotBlank(json)) {
            List<ShopType> list = JSONUtil.toList(json, ShopType.class);
            return Result.ok(list);
        }
//        QueryWrapper<ShopType> qw = new QueryWrapper<>();
//        qw.orderByAsc("sort");
//        typeService.list(qw);下面等价于
        // 3. 查数据库
        List<ShopType> list = this.lambdaQuery()
                .orderByAsc(ShopType::getSort)
                .list();

        // 4. 存入 Redis（空也可以不存）
        if (list != null && !list.isEmpty()) {
            stringRedisTemplate.opsForValue().set(
                    CACHE_KEY, JSONUtil.toJsonStr(list), 30, TimeUnit.MINUTES);
        }
        // 5. 返回结果
        return Result.ok(list);
    }
}
