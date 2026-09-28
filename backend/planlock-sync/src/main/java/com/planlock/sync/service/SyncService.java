package com.planlock.sync.service;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.schedule.ScheduleRules;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.sync.dto.SyncItemResult;
import com.planlock.sync.dto.SyncPullResponse;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.dto.SyncPushResponse;
import com.planlock.sync.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步服务（sync 模块）：全量快照 + 版本号冲突检测。
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final SyncMapper syncMapper;
    private final ObjectMapper objectMapper;

    public SyncPullResponse pull(Long userId) {
        return SyncPullResponse.builder()
                .plans(syncMapper.pullPlans(userId))
                .schedules(syncMapper.pullSchedules(userId))
                .strategies(syncMapper.pullStrategies(userId))
                .recordCounts(Map.of(
                        "execution", syncMapper.countExecutions(userId),
                        "violation", syncMapper.countViolations(userId),
                        "unlock", syncMapper.countUnlocks(userId)))
                .build();
    }

    @Transactional
    public SyncPushResponse push(Long userId, SyncPushRequest req) {
        List<SyncItemResult> results = new ArrayList<>();
        if (req.getPlans() != null) {
            for (SyncPushRequest.PlanItem item : req.getPlans()) {
                String key = "plan:" + item.getId();
                try {
                    pushPlan(userId, item);
                    results.add(ok(key));
                } catch (BusinessException e) {
                    results.add(fail(key, e));
                }
            }
        }
        if (req.getStrategies() != null) {
            for (SyncPushRequest.StrategyItem item : req.getStrategies()) {
                String key = "strategy:" + item.getPlanId();
                try {
                    pushStrategy(userId, item);
                    results.add(ok(key));
                } catch (BusinessException e) {
                    results.add(fail(key, e));
                }
            }
        }
        if (req.getSchedules() != null) {
            for (SyncPushRequest.ScheduleItem item : req.getSchedules()) {
                String key = "schedule:" + item.getId();
                try { pushSchedule(userId,item); results.add(ok(key)); }
                catch (BusinessException e) { results.add(fail(key,e)); }
            }
        }
        return new SyncPushResponse(results);
    }

    private void pushSchedule(Long userId, SyncPushRequest.ScheduleItem item) {
        // 两个写入口共用时间与重复规则，避免同步绕过 CRUD 校验。
        ScheduleUpsertRequest rulesInput = new ScheduleUpsertRequest();
        rulesInput.setStartDate(item.getStartDate());
        rulesInput.setEndDate(item.getEndDate());
        rulesInput.setStartTime(item.getStartTime());
        rulesInput.setEndTime(item.getEndTime());
        rulesInput.setAllDay(item.getAllDay());
        rulesInput.setRepeatRule(item.getRepeatRule());
        ScheduleRules.validate(rulesInput, objectMapper);
        if (item.getStatus() == null) item.setStatus(1);
        if (item.getId() == null) { syncMapper.insertSchedule(userId,item); return; }
        if (item.getBaseVersion() == null) throw new BusinessException(ErrorCode.INVALID_PARAM,"缺少 baseVersion");
        if (syncMapper.updateSchedule(userId,item)==0) {
            if (syncMapper.countOwnedSchedule(item.getId(),userId)==0) throw new BusinessException(ErrorCode.NOT_FOUND,"日程不存在");
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void pushPlan(Long userId, SyncPushRequest.PlanItem item) {
        normalizePlan(item);
        if (item.getId() == null) {
            syncMapper.insertPlan(userId, item);
            return;
        }
        if (item.getBaseVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        int updated = syncMapper.updatePlan(userId, item);
        if (updated == 0) {
            if (syncMapper.countOwnedPlan(item.getId(), userId) == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void pushStrategy(Long userId, SyncPushRequest.StrategyItem item) {
        if (syncMapper.countOwnedPlan(item.getPlanId(), userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        if (syncMapper.countStrategy(item.getPlanId()) == 0) {
            syncMapper.insertStrategy(item);
            return;
        }
        if (item.getBaseVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        int updated = syncMapper.updateStrategy(item);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void normalizePlan(SyncPushRequest.PlanItem item) {
        if (item.getRemindAdvanceMin() == null) {
            item.setRemindAdvanceMin(15);
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
    }

    private SyncItemResult ok(String key) {
        return SyncItemResult.builder().key(key).ok(true).code(0).message("ok").build();
    }

    private SyncItemResult fail(String key, BusinessException e) {
        return SyncItemResult.builder().key(key).ok(false).code(e.getCode()).message(e.getMessage()).build();
    }
}
