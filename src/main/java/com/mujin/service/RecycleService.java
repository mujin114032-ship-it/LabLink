package com.mujin.service;

import com.mujin.domain.dto.FileQueryDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

public interface RecycleService {
    Map<String, Object> getRecycleList(FileQueryDTO dto);

    void restoreFiles(List<String> ids, Long userId);

    void hardDeleteFiles(List<String> ids, Long userId);

    void clearRecycleBin(Long userId);
}
