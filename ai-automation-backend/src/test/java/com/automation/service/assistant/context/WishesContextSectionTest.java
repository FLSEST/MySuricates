package com.automation.service.assistant.context;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishesContextSectionTest {

    @Mock private WishRepository wishRepository;

    private WishesContextSection section;

    @BeforeEach
    void setUp() {
        section = new WishesContextSection(wishRepository);
    }

    private Wish buildWish(String id, String text, WishStatus status) {
        Wish wish = new Wish();
        wish.setId(id);
        wish.setOriginalText(text);
        wish.setStatus(status);
        return wish;
    }

    @Test
    void compact_showsCounts() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildWish("11111111-abcd", "Active wish", WishStatus.EXECUTING),
                buildWish("22222222-efgh", "Failed wish", WishStatus.FAILED)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString())
                .contains("1 active")
                .contains("1 failed")
                .contains("22222222");
    }

    @Test
    void compact_noWishes_appendsNothing() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void compact_onlyCompleted_appendsNothing() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildWish("id1", "Done", WishStatus.COMPLETED)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsActiveWishes() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildWish("id1", "Deploy app", WishStatus.EXECUTING),
                buildWish("id2", "Review code", WishStatus.DECOMPOSING)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Active Wishes (2):")
                .contains("[EXECUTING] Deploy app")
                .contains("[DECOMPOSING] Review code");
    }

    @Test
    void full_showsFailedWishes() {
        Wish failed = buildWish("id1", "Broken wish", WishStatus.FAILED);
        failed.setError("Timeout occurred");
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(failed));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Recent Failed Wishes:")
                .contains("[FAILED] Broken wish")
                .contains("Timeout occurred");
    }

    @Test
    void full_showsCancelledAsFailed() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildWish("id1", "Cancelled wish", WishStatus.CANCELLED)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("[CANCELLED] Cancelled wish");
    }

    @Test
    void full_limitsTo5Each() {
        List<Wish> wishes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            wishes.add(buildWish("active-" + i, "Active " + i, WishStatus.EXECUTING));
        }
        for (int i = 0; i < 8; i++) {
            wishes.add(buildWish("failed-" + i, "Failed " + i, WishStatus.FAILED));
        }
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(wishes);

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String result = sb.toString();
        long activeCount = result.lines().filter(l -> l.contains("[EXECUTING]")).count();
        long failedCount = result.lines().filter(l -> l.contains("[FAILED]")).count();
        assertThat(activeCount).isLessThanOrEqualTo(5);
        assertThat(failedCount).isLessThanOrEqualTo(5);
    }

    @Test
    void exception_doesNotThrow() {
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenThrow(new RuntimeException("error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}
