package com.automation.mapper;

import com.automation.dto.response.ContainerResponse;
import com.automation.entity.Container;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ContainerMapper {

    ContainerResponse toResponse(Container container);
}
