package com.automation.mapper;

import com.automation.dto.response.DeadLetterResponse;
import com.automation.entity.DeadLetterEntry;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DeadLetterMapper {

    DeadLetterResponse toResponse(DeadLetterEntry entry);
}
