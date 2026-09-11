package com.automation.service.scheduler;

import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerMaintenanceModeTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @InjectMocks private SchedulerMaintenanceMode maintenanceMode;

    @Test
    void isEnabled_readsTrueFlag() {
        when(appSettingsRepository.get("scheduler.maintenance-mode")).thenReturn(Optional.of("true"));

        assertThat(maintenanceMode.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_missingOrOtherValue_isFalse() {
        when(appSettingsRepository.get("scheduler.maintenance-mode")).thenReturn(Optional.empty());

        assertThat(maintenanceMode.isEnabled()).isFalse();
    }

    @Test
    void setEnabled_persistsFlag() {
        maintenanceMode.setEnabled(true);

        verify(appSettingsRepository).set("scheduler.maintenance-mode", "true");
    }

    @Test
    void setEnabled_disabledPersistsFalse() {
        maintenanceMode.setEnabled(false);

        verify(appSettingsRepository).set("scheduler.maintenance-mode", "false");
    }
}
