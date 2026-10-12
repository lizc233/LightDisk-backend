package com.star.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.entity.Share;
import com.star.model.vo.ShareInfoVO;

import java.util.List;

public interface ShareService extends IService<Share> {

    ShareInfoVO createShare(ShareCreateDTO dto, Long currentUserId);

    /**
     * 批量创建分享：一次为多个文件生成分享链接（返回生成结果，单个失败自动跳过）
     */
    List<ShareInfoVO> batchCreateShare(List<Long> fileIds, Integer expireDays, Long currentUserId);

    Page<ShareInfoVO> getShareList(ShareCreateDTO dto, Long currentUserId);

    int cancelShares(List<Long> shareIds, Long currentUserId);

    int clearExpiredShares(Long currentUserId);

    ShareInfoVO getPublicShareInfo(String shareCode);

    String downloadPublicShare(String shareCode, String extractCode);

    Long savePublicShareToMyDisk(String shareCode, String extractCode, Long currentUserId);
}
