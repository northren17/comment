package com.hmdp.HandlerInterceptor;

import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码并返回 JSON 说明
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            try {
                String body = Result.fail("NOT_LOGIN").toString();
                response.getWriter().write(body);
                response.getWriter().flush();
            } catch (IOException ignored) {
            }
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }
}