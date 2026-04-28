package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleRedisLock  implements ILock {
    public static  final  String KEY_PREFIX="lock:";
    private static  final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
@Resource
private StringRedisTemplate stringRedisTemplate;
private String name;

    public SimpleRedisLock(String name) {
        this.name = name;
    }
    @Override
    /*
    加锁
     */
    public boolean tryLock(long timeOutSec) {
       String threadId=ID_PREFIX+Thread.currentThread().getId();
       Boolean success=stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId,timeOutSec, TimeUnit.SECONDS);
       return  Boolean.TRUE.equals(success);

    }
public  static  final DefaultRedisScript <Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript();
        UNLOCK_SCRIPT.setResultType(Long.class);
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }

    @Override
    /*
    释放锁
     */
    public void unlock() {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
     stringRedisTemplate.execute(
             UNLOCK_SCRIPT,
             Collections.singletonList(KEY_PREFIX+name),
             threadId);
    }
}
