package com.planlock.record.mapper;

import com.planlock.record.entity.ViolationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * violation_records 表 Mapper（record 模块）。
 */
@Mapper
public interface ViolationRecordMapper {

    @Insert("INSERT INTO violation_records(plan_id, device_id, record_at, app_name, package_name, client_id) "
            + "VALUES(#{planId}, #{deviceId}, #{recordAt}, #{appName}, #{packageName}, #{clientId})")
    int insert(ViolationRecord record);

    @Select("SELECT v.* FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.record_at BETWEEN #{start} AND #{end} ORDER BY v.record_at DESC")
    List<ViolationRecord> findByUserBetween(@Param("userId") Long userId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Select("SELECT v.* FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} ORDER BY v.id DESC")
    List<ViolationRecord> findAllByUser(@Param("userId") Long userId);
}
