package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

public interface IUserService extends IService<User> {

    Result sign();

    Result signCount();
}