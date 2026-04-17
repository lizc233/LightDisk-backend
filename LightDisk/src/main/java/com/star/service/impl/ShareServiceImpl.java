package com.star.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.entity.Share;
import com.star.model.vo.ShareInfoVO;
import com.star.service.ShareService;
import com.star.mapper.ShareMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
* @author Lizc233
* @description 针对表【share(分享表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:42
*/
@Service
public class ShareServiceImpl extends ServiceImpl<ShareMapper, Share>
    implements ShareService{
    @Autowired
    private StringRedisTemplate redisTemplate;

    // @Autowired
    // private FileMapper fileMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareInfoVO createShare(ShareCreateDTO dto, Long currentUserId) {
        /*
        1. 校验文件是否存在 (由于伟伟还没写完，这步暂时注释掉，直接假设文件存在)
        File userFile = fileMapper.selectOne(new LambdaQueryWrapper<File>()
                .eq(File::getId, dto.getFileId())
                .eq(File::getUserId, currentUserId)
                .eq(File::getStatus, 1));
        if (userFile == null) {
            throw new RuntimeException("文件不存在或已被删除");
        }
        */

        // 2. 生成分享属性
        String extractCode = RandomUtil.randomString(4).toLowerCase(); // 4位随机提取码，如 a8b2
        String shareCode = RandomUtil.randomString(8); // 8位短链码

        // 3. 计算过期时间
        LocalDateTime expireTime = null;
        long redisExpireSeconds = 0;

        if (dto.getExpireType() == null || dto.getExpireType() == 0) {
            // 0 表示永久有效（或者是前端忘传了，我们默认永久）
            expireTime = null;
        } else if (dto.getExpireType() == 1) { // 7天
            expireTime = LocalDateTime.now().plusDays(7);
            redisExpireSeconds = 7 * 24 * 60 * 60;
        } else if (dto.getExpireType() == 2) { // 30天
            expireTime = LocalDateTime.now().plusDays(30);
            redisExpireSeconds = 30 * 24 * 60 * 60;
        } else {
            // 兜底逻辑：防止前端乱传其他数字，强制默认为 7 天
            expireTime = LocalDateTime.now().plusDays(7);
            redisExpireSeconds = 7 * 24 * 60 * 60;
        }

        // 4. 存入 MySQL (对应你的 share 表)
        Share share = new Share();
        share.setShareCode(shareCode);
        share.setItemType(1); // 1表示文件
        share.setItemId(dto.getFileId());
        share.setUserId(currentUserId);
        share.setExtractCode(extractCode);
        share.setExpireTime(expireTime);
        share.setStatus(1);
        this.save(share);

        // 5. 存入 Redis (如果是永久有效 expireType == 0，就不存Redis或者设为不超时)
        if (redisExpireSeconds > 0) {
            String redisKey = "share:" + shareCode;
            redisTemplate.opsForValue().set(redisKey, extractCode, redisExpireSeconds, TimeUnit.SECONDS);
        }

        // 6. 返回给前端展示
        ShareInfoVO vo = new ShareInfoVO();
        // 这里拼接假的前端地址，等前端写好了再改
        vo.setShareUrl("http://localhost:8080/share_view/" + shareCode);
        vo.setExtractCode(extractCode);
        return vo;
    }
}




