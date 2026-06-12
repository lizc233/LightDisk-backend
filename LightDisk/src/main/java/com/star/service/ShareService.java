package com.star.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.entity.Share;
import com.star.model.vo.ShareInfoVO;

import java.util.List;

public interface ShareService extends IService<Share> {

    ShareInfoVO createShare(ShareCreateDTO dto, Long currentUserId);

    Page<ShareInfoVO> getShareList(ShareCreateDTO dto, Long currentUserId);

    int cancelShares(List<Long> shareIds, Long currentUserId);

    int clearExpiredShares(Long currentUserId);

    ShareInfoVO getPublicShareInfo(String shareCode);

    String downloadPublicShare(String shareCode, String extractCode);

    Long savePublicShareToMyDisk(String shareCode, String extractCode, Long currentUserId);
}
