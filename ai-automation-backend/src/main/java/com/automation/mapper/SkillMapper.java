package com.automation.mapper;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.dto.response.CapabilityResponse;
import com.automation.dto.response.SkillResponse;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SkillMapper {

    SkillResponse toResponse(Skill skill);

    List<SkillResponse> toResponseList(List<Skill> skills);

    CapabilityResponse toCapabilityResponse(Capability capability);

    List<CapabilityResponse> toCapabilityResponseList(List<Capability> capabilities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "builtin", constant = "false")
    @Mapping(target = "version", constant = "1.0.0")
    @Mapping(target = "capabilities", ignore = true)
    @Mapping(target = "workflows", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Skill toEntity(CreateSkillRequest request);
}
