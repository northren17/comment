package com.hmdp.utils;

/**
 * 手机/正则常量
 */
public abstract class RegexPatterns {
    /**
     * 手机号正则：仅允许 11 位阿拉伯数字
     */
    public static final String PHONE_REGEX = "^[0-9]{11}$";
    /**
     * 邮箱正则
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    /**
     * 密码正则。4~32位的字母、数字、下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    /**
     * 验证码正则, 6位数字或字母
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
