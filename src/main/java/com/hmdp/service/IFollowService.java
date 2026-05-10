package com.hmdp.service;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;

public interface IFollowService extends IService<Follow> {
    /**
     * 关注或取关某用户
     * @param followUserId 被关注的用户 id
     * @param isFollow true 表示关注，false 表示取关
     * @return Result
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断是否已关注某用户
     * @param followUserId 被检查的用户 id
     * @return Result（data 为 boolean）
     */
    Result isFollow(Long followUserId);

    /**
     * 查询共同关注
     * @param id 另一个用户id
     * @return Result（data为共同关注的用户列表）
     */
    Result followCommons(Long id);
}
