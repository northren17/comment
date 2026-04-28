package com.hmdp.utils;

public interface ILock {
    /*
    获取锁
     */
    boolean tryLock(long timeOutSec);

    void unlock();
}
