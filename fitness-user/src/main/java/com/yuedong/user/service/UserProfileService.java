package com.yuedong.user.service;

import com.yuedong.user.model.dto.UserProfileSaveDTO;
import com.yuedong.user.model.vo.UserProfileVO;

/**
 * 健身档案业务接口（MySQL t_user_profile + 读一次 Mongo 的最近体重）。
 */
public interface UserProfileService {

    /** 取档案。从没建过也返回一个可用的默认（目标未设、跟踪项=全集），前端不必判空 */
    UserProfileVO get(Long userId);

    /** 保存档案。★传 null 的字段保持原值，不做"整行覆盖" */
    UserProfileVO save(Long userId, UserProfileSaveDTO dto);
}
