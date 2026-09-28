package com.planlock.record.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * violation_records 表实体。
 */
@Data
public class ViolationRecord {

    private Long id;
    private Long planId;
    private Long deviceId;
    private LocalDateTime recordAt;
    private String appName;
    private String packageName;
    private String clientId;
    private LocalDateTime createdAt;
}
