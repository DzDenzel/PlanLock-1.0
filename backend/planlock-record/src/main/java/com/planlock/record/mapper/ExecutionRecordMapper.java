package com.planlock.record.mapper;

import com.planlock.record.entity.ExecutionRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * execution_records 表 Mapper（record 模块）。
 */
@Mapper
public interface ExecutionRecordMapper {

    @Insert("INSERT INTO execution_records(plan_id, device_id, plan_date, status, started_at, ended_at, client_id) "
            + "VALUES(#{planId}, #{deviceId}, #{planDate}, #{status}, #{startedAt}, #{endedAt}, #{clientId})")
    int insert(ExecutionRecord record);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND e.plan_date = #{date} ORDER BY e.id DESC")
    List<ExecutionRecord> findByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND e.plan_date BETWEEN #{start} AND #{end} AND e.status = 1 "
            + "ORDER BY e.plan_date")
    List<ExecutionRecord> findCompletedBetween(@Param("userId") Long userId,
                                               @Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} ORDER BY e.id DESC")
    List<ExecutionRecord> findAllByUser(@Param("userId") Long userId);
}
