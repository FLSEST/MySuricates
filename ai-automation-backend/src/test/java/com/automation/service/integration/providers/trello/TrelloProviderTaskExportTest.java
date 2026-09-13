package com.automation.service.integration.providers.trello;

import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrelloProviderTaskExportTest {

    @Mock private TrelloHttpClient http;
    private TrelloProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TrelloProvider(new ObjectMapper(), http);
    }

    private static TrelloCard card(String id, String shortUrl) {
        return new TrelloCard(id, "n", "d", "https://trello.com/c/full", shortUrl, false, "b1", "l1", null, false,
                List.of(), List.of(), 0f);
    }

    @Test
    void createTask_postsCardIntoList_andReturnsShortUrl() {
        when(http.post(contains("/cards?"), eq(TrelloCard.class))).thenReturn(card("c1", "https://trello.com/c/abc"));

        TaskExportCapable.TaskRef ref = provider.createTask(
                new TaskExportCapable.TaskExportInput("Fix login", "Steps & notes", List.of("bug"), "l1"), "key1|tok1");

        assertThat(ref.referenceType()).isEqualTo("trello");
        assertThat(ref.referenceId()).isEqualTo("c1");
        assertThat(ref.url()).isEqualTo("https://trello.com/c/abc");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(http).post(url.capture(), eq(TrelloCard.class));
        assertThat(url.getValue())
                .startsWith("https://api.trello.com/1/cards?key=key1&token=tok1")
                .contains("&idList=l1").contains("&name=Fix+login").contains("&desc=Steps+%26+notes").contains("&pos=bottom");
    }

    @Test
    void createTask_blankTarget_failsBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.createTask(
                new TaskExportCapable.TaskExportInput("t", "b", List.of(), ""), "key1|tok1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list id");
        verifyNoInteractions(http);
    }

    @Test
    void listTargets_flattensOpenListsOfOpenBoards() {
        when(http.get(contains("/members/me/boards?"), eq(TrelloBoard[].class))).thenReturn(new TrelloBoard[]{
                new TrelloBoard("b1", "Board", null, null, false, null)});
        when(http.get(contains("/boards/b1/lists?"), eq(TrelloList[].class))).thenReturn(new TrelloList[]{
                new TrelloList("l1", "To Do", false, "b1", 1f),
                new TrelloList("l2", "Done", false, "b1", 2f)});

        List<TaskExportCapable.TaskTarget> targets = provider.listTargets("key1|tok1");

        assertThat(targets).containsExactly(
                new TaskExportCapable.TaskTarget("l1", "Board / To Do"),
                new TaskExportCapable.TaskTarget("l2", "Board / Done"));
    }
}
