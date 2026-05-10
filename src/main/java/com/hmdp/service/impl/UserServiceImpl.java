package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户签到功能
     * 使用Redis的BitMap数据结构实现高效的签到记录存储
     * 每个用户每月一个key，每一位代表一天的签到状态
     *
     * @return 签到结果，成功返回ok
     */
    @Override
    public Result sign() {
        // 获取当前登录用户ID
        Long userId = UserHolder.getUser().getId();
        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        // 构建日期后缀：格式为 :yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 拼接完整的Redis key：USER_SIGN_KEY + userId + :yyyyMM
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取当前日期（1-31）
        int dayOfMonth = now.getDayOfMonth();
        // 将对应日期的位设置为true，表示已签到（BitMap索引从0开始，所以减1）
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        // 返回签到成功结果
        return Result.ok();
    }

    /**
     * 获取用户本月签到次数
     * 使用Redis的BitField命令获取连续的签到位，然后统计1的个数
     *
     * @return 本月累计签到次数
     */
    @Override
    public Result signCount() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        // 拼接Redis key：格式为 sign:userId:yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天（1-31）
        int dayOfMonth = now.getDayOfMonth();
        // 使用BitField命令获取本月截止今天的所有签到记录
        // GET u{dayOfMonth} 0 表示从第0位开始，获取dayOfMonth位的无符号整数
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                org.springframework.data.redis.connection.BitFieldSubCommands.create()
                  .get(org.springframework.data.redis.connection.BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到记录
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 统计二进制中1的个数（已签到天数）
        int count = 0;
        while (num > 0) {
            // 与1做与运算，获取最后一位
            if ((num & 1) != 0) {
                count++;
            }
            // 右移一位，处理下一位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}