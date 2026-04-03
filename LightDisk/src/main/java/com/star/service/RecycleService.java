package com.star.service;


import com.star.common.Result;
import com.star.model.dto.RecycleOperateDTO;
import com.star.model.vo.RecycleVO;
import org.springframework.context.annotation.Bean;

import java.util.List;


public interface RecycleService {

    Result<List<RecycleVO>> getRecycleListBy0();

    Result<Boolean> deleteRecycle(RecycleOperateDTO dto);

    Result<Boolean> restoreFile(RecycleOperateDTO dto);

    Result<Boolean> deleteFile(RecycleOperateDTO dto);
}
