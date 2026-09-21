package com.automation.service.dashboard;

import com.automation.config.DashboardLayoutConfig;
import com.automation.dto.response.DashboardLayoutVersionListItem;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.DashboardLayoutVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardLayoutVersionServiceTest {

    private static final JsonNode BODY = new ObjectMapper().createObjectNode();

    @Mock private DashboardLayoutVersionRepository repository;
    @Mock private DashboardLayoutValidator validator;

    private final DashboardLayoutConfig config = new DashboardLayoutConfig();
    private DashboardLayoutVersionService service;

    @BeforeEach
    void setUp() {
        service = new DashboardLayoutVersionService(repository, validator, config);
    }

    private static ValidatedLayoutSave save(String baseVersionId, boolean force, String hash) {
        return new ValidatedLayoutSave("Laptop", 2, baseVersionId, force, "{\"c\":1}", hash, 1);
    }

    private static DashboardLayoutVersionListItem latest(String id, String hash, Instant createdAt) {
        return new DashboardLayoutVersionListItem() {
            public String getId() { return id; }
            public Instant getCreatedAt() { return createdAt; }
            public String getDeviceLabel() { return "Phone"; }
            public String getName() { return null; }
            public int getFormatVersion() { return 2; }
            public int getViewCount() { return 1; }
            public String getContentHash() { return hash; }
        };
    }

    private void savesReturnTheirArgument() {
        when(repository.save(any(DashboardLayoutVersion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void list_clamps_page_and_size() {
        Page<DashboardLayoutVersionListItem> empty = new PageImpl<>(List.of());
        when(repository.findAllByOrderByCreatedAtDescIdDesc(any())).thenReturn(empty);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        service.list(-3, 0);
        service.list(2, 500);

        verify(repository, org.mockito.Mockito.times(2)).findAllByOrderByCreatedAtDescIdDesc(pageable.capture());
        assertThat(pageable.getAllValues()).containsExactly(PageRequest.of(0, 1), PageRequest.of(2, 100));
    }

    @Test
    void get_throws_not_found_for_an_unknown_id() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void first_save_writes_a_version_and_prunes() {
        when(validator.validateSave(BODY)).thenReturn(save(null, false, "h1"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        savesReturnTheirArgument();

        DashboardLayoutVersionService.SaveResult result = service.save(BODY);

        assertThat(result.created()).isTrue();
        DashboardLayoutVersion version = result.version();
        assertThat(version.getDeviceLabel()).isEqualTo("Laptop");
        assertThat(version.getFormatVersion()).isEqualTo(2);
        assertThat(version.getViewCount()).isEqualTo(1);
        assertThat(version.getContentHash()).isEqualTo("h1");
        assertThat(version.getContent()).isEqualTo("{\"c\":1}");
        assertThat(version.getName()).isNull();
        verify(repository).pruneUnnamed(100);
    }

    @Test
    void unchanged_content_returns_the_newest_version_without_writing() {
        DashboardLayoutVersion existing = DashboardLayoutVersion.builder().id("v1").contentHash("h1").build();
        when(validator.validateSave(BODY)).thenReturn(save("stale-base", false, "h1"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(latest("v1", "h1", Instant.now())));
        when(repository.findById("v1")).thenReturn(Optional.of(existing));

        DashboardLayoutVersionService.SaveResult result = service.save(BODY);

        // A stale base does not matter when nothing changes.
        assertThat(result.created()).isFalse();
        assertThat(result.version()).isSameAs(existing);
        verify(repository, never()).save(any());
        verify(repository, never()).pruneUnnamed(anyInt());
    }

    @Test
    void stale_base_is_a_conflict() {
        when(validator.validateSave(BODY)).thenReturn(save("v0", false, "h2"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(latest("v1", "h1", Instant.now())));

        assertThatThrownBy(() -> service.save(BODY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another device");
        verify(repository, never()).save(any());
    }

    @Test
    void missing_base_with_an_existing_version_is_a_conflict_too() {
        when(validator.validateSave(BODY)).thenReturn(save(null, false, "h2"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(latest("v1", "h1", Instant.now())));

        assertThatThrownBy(() -> service.save(BODY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void force_overrides_a_stale_base() {
        when(validator.validateSave(BODY)).thenReturn(save("v0", true, "h2"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(latest("v1", "h1", Instant.now().minusSeconds(60))));
        savesReturnTheirArgument();

        assertThat(service.save(BODY).created()).isTrue();
    }

    @Test
    void matching_base_saves_after_the_newest_version() {
        Instant before = Instant.now().minusSeconds(60);
        when(validator.validateSave(BODY)).thenReturn(save("v1", false, "h2"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.of(latest("v1", "h1", before)));
        savesReturnTheirArgument();

        assertThat(service.save(BODY).version().getCreatedAt()).isAfter(before);
    }

    @Test
    void created_at_is_bumped_past_a_newest_version_in_the_same_or_a_later_millisecond() {
        Instant future = Instant.now().plusSeconds(3600);
        when(validator.validateSave(BODY)).thenReturn(save("v1", false, "h2"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.of(latest("v1", "h1", future)));
        savesReturnTheirArgument();

        assertThat(service.save(BODY).version().getCreatedAt()).isEqualTo(future.plusMillis(1));
    }

    @Test
    void retention_of_zero_or_less_still_keeps_the_version_just_saved() {
        config.setRetentionCount(0);
        when(validator.validateSave(BODY)).thenReturn(save(null, false, "h1"));
        when(repository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        savesReturnTheirArgument();

        service.save(BODY);

        verify(repository).pruneUnnamed(1);
    }

    @Test
    void validation_errors_propagate_before_any_repository_call() {
        when(validator.validateSave(BODY)).thenThrow(new IllegalArgumentException("Invalid dashboard layout: x"));

        assertThatThrownBy(() -> service.save(BODY)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findFirstByOrderByCreatedAtDescIdDesc();
    }

    @Test
    void rename_sets_the_validated_name() {
        DashboardLayoutVersion existing = DashboardLayoutVersion.builder().id("v1").build();
        when(validator.validateRename(BODY)).thenReturn("Release");
        when(repository.findById("v1")).thenReturn(Optional.of(existing));
        savesReturnTheirArgument();

        assertThat(service.rename("v1", BODY).getName()).isEqualTo("Release");
    }

    @Test
    void rename_of_an_unknown_version_is_not_found() {
        when(validator.validateRename(BODY)).thenReturn("Release");
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename("missing", BODY)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }
}
