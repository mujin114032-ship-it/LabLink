package com.mujin.domain.dto;
import lombok.Data;
import java.util.List;

@Data
public class RecycleOperationDTO {
    private List<String> ids; // 接收 ["f_1001", "f_1002"]
}
