package com.automation.mapper;

import com.automation.dto.response.ActivityLogResponse;
import com.automation.entity.ActivityLog;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ActivityLogMapper {

    ActivityLogResponse toResponse(ActivityLog log);

    List<ActivityLogResponse> toResponseList(List<ActivityLog> logs);
}
