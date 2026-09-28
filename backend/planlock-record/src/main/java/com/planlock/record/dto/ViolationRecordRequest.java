package com.planlock.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违规尝试记录上报请求。
 */
@Data
public class ViolationRecordRequest {

    @NotNull(message = "计划 ID 不能为空")
    private Long planId;

    @NotNull(message = "违规时间不能为空")
    private LocalDateTime recordAt;

    private String appName;

    private String packageName;

    @NotBlank(message = "clientId 不能为空")
    private String clientId;
}
