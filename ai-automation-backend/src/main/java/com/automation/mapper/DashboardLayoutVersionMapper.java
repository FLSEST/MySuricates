package com.automation.mapper;

import com.automation.dto.response.DashboardLayoutVersionSummary;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.repository.DashboardLayoutVersionListItem;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface DashboardLayoutVersionMapper {

    DashboardLayoutVersionSummary toSummary(DashboardLayoutVersion version);

    DashboardLayoutVersionSummary toSummary(DashboardLayoutVersionListItem item);

    List<DashboardLayoutVersionSummary> toSummaries(List<DashboardLayoutVersionListItem> items);
}
