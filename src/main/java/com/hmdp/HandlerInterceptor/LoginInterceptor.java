package com.hmdp.HandlerInterceptor;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO; // 1. 导入 UserDTO
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;


public class LoginInterceptor implements HandlerInterceptor {

private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求体中的token
    String token=request.getHeader("authorization");
        if(StringUtils.isBlank(token))
        {
            response.setStatus(401);
            return false;
        }
        //2.获取redis对应的token对应用户
       Map<Object,Object> usermap= stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        //3.判断用户是否存在
        if(usermap.isEmpty()){
            //4.不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        //5.存在，保存用户信息到Threadlocal
        // 【修改点】查询的hash 改为 (UserDTO)
      UserDTO userDTO=  BeanUtil.fillBeanWithMap(usermap,new UserDTO(),false);
        //保存用户信息到threadlocal
        UserHolder.saveUser(userDTO);
        //刷新过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6.放行
        return true;
    }
}