package com.automation.mapper;

import com.automation.dto.response.NotificationConfigResponse;
import com.automation.entity.NotificationConfig;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface NotificationConfigMapper {

    NotificationConfigResponse toResponse(NotificationConfig config);

    List<NotificationConfigResponse> toResponseList(List<NotificationConfig> configs);
}
