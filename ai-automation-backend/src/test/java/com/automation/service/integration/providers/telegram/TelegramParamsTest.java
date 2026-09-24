package com.automation.service.integration.providers.telegram;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Direct tests of the pure parameter parsing; the HTTP side is {@code TelegramProviderTest}. */
class TelegramParamsTest {

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Nested
    class ExtractToken {

        @Test
        void labelAndToken_returnsTheTokenPart() {
            assertThat(TelegramParams.extractToken("My bot|123:ABC")).isEqualTo("123:ABC");
        }

        @Test
        void withoutLabel_returnsTheWholeValue() {
            assertThat(TelegramParams.extractToken("123:ABC")).isEqualTo("123:ABC");
        }

        @Test
        void trimsWhitespace_andKeepsLaterPipes() {
            assertThat(TelegramParams.extractToken(" bot |  123:A|B \n")).isEqualTo("123:A|B");
        }

        @Test
        void nullStaysNull_emptyTokenIsEmpty() {
            assertThat(TelegramParams.extractToken(null)).isNull();
            assertThat(TelegramParams.extractToken("label|")).isEmpty();
        }
    }

    @Nested
    class ChatId {

        @Test
        void numberParam_becomesLong() {
            assertThat(TelegramParams.chatId(params("chat_id", 42))).isEqualTo(42L);
            assertThat(TelegramParams.chatId(params("chat_id", -1001234567890L))).isEqualTo(-1001234567890L);
        }

        @Test
        void numericString_becomesLong_evenWithWhitespace() {
            assertThat(TelegramParams.chatId(params("chat_id", " -1001234567890 "))).isEqualTo(-1001234567890L);
        }

        @Test
        void channelUsername_staysString() {
            assertThat(TelegramParams.chatId(params("chat_id", "@my_channel"))).isEqualTo("@my_channel");
        }

        @Test
        void missingOrBlank_isRequired() {
            assertThatThrownBy(() -> TelegramParams.chatId(params()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Required parameter missing: chat_id");
            assertThatThrownBy(() -> TelegramParams.chatId(params("chat_id", "  ")))
                    .hasMessage("Required parameter missing: chat_id");
        }

        @Test
        void usernameTooShortOrNotStartingWithALetter_isRejected() {
            for (String bad : new String[] {"@abc", "@1channel", "channel", "@with-dash", "@" + "a".repeat(33), "12a"}) {
                assertThatThrownBy(() -> TelegramParams.chatId(params("chat_id", bad)))
                        .as(bad)
                        .hasMessage("Parameter 'chat_id' must be a numeric chat id or @channelusername");
            }
        }

        @Test
        void usernameAtTheLengthLimits_isAccepted() {
            assertThat(TelegramParams.chatId(params("chat_id", "@abcd"))).isEqualTo("@abcd");
            String longest = "@a" + "b".repeat(31);
            assertThat(TelegramParams.chatId(params("chat_id", longest))).isEqualTo(longest);
        }

        /** Twenty digits pass the pattern but do not fit a long; before, this leaked "For input string". */
        @Test
        void numberBeyondLongRange_isRejectedWithTheParameterMessage() {
            assertThatThrownBy(() -> TelegramParams.chatId(params("chat_id", "99999999999999999999")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'chat_id' must be a numeric chat id or @channelusername");
        }
    }

    @Nested
    class Text {

        @Test
        void keepsTheTextVerbatim() {
            assertThat(TelegramParams.text(params("text", "  Hallo\nWelt "))).isEqualTo("  Hallo\nWelt ");
        }

        @Test
        void nonStringValue_usesToString() {
            assertThat(TelegramParams.text(params("text", 42))).isEqualTo("42");
        }

        @Test
        void missingOrBlank_isRequired() {
            assertThatThrownBy(() -> TelegramParams.text(params()))
                    .hasMessage("Required parameter missing: text");
            assertThatThrownBy(() -> TelegramParams.text(params("text", " \n")))
                    .hasMessage("Required parameter missing: text");
        }

        @Test
        void exactlyTheLimit_isAccepted_oneMoreIsRejected() {
            assertThat(TelegramParams.text(params("text", "x".repeat(TelegramParams.MAX_TEXT))))
                    .hasSize(4096);
            assertThatThrownBy(() -> TelegramParams.text(params("text", "x".repeat(TelegramParams.MAX_TEXT + 1))))
                    .hasMessage("Parameter 'text' is longer than 4096 characters");
        }
    }

    @Nested
    class ParseMode {

        @Test
        void missingOrBlank_meansPlainText() {
            assertThat(TelegramParams.parseMode(params())).isNull();
            assertThat(TelegramParams.parseMode(params("parse_mode", " "))).isNull();
        }

        @Test
        void supportedModes_areTrimmed() {
            assertThat(TelegramParams.parseMode(params("parse_mode", "MarkdownV2"))).isEqualTo("MarkdownV2");
            assertThat(TelegramParams.parseMode(params("parse_mode", " HTML "))).isEqualTo("HTML");
        }

        @Test
        void legacyMarkdownAndWrongCase_areRejected() {
            for (String bad : new String[] {"Markdown", "html", "markdownv2"}) {
                assertThatThrownBy(() -> TelegramParams.parseMode(params("parse_mode", bad)))
                        .as(bad)
                        .hasMessage("Parameter 'parse_mode' must be MarkdownV2 or HTML");
            }
        }
    }

    @Nested
    class Bool {

        @Test
        void missingOrBlank_isNull() {
            assertThat(TelegramParams.bool(params(), "silent")).isNull();
            assertThat(TelegramParams.bool(params("silent", ""), "silent")).isNull();
        }

        @Test
        void booleanValue_isTakenAsIs() {
            assertThat(TelegramParams.bool(params("silent", true), "silent")).isTrue();
            assertThat(TelegramParams.bool(params("silent", false), "silent")).isFalse();
        }

        @Test
        void strings_ignoreCaseAndWhitespace() {
            assertThat(TelegramParams.bool(params("silent", " TRUE "), "silent")).isTrue();
            assertThat(TelegramParams.bool(params("silent", "False"), "silent")).isFalse();
        }

        @Test
        void anythingElse_namesTheKey() {
            assertThatThrownBy(() -> TelegramParams.bool(params("silent", "yes"), "silent"))
                    .hasMessage("Parameter 'silent' must be true or false");
            assertThatThrownBy(() -> TelegramParams.bool(params("silent", 1), "silent"))
                    .hasMessage("Parameter 'silent' must be true or false");
        }
    }

    @Nested
    class OptionalLong {

        @Test
        void missingOrBlank_isNull() {
            assertThat(TelegramParams.optionalLong(params(), "offset")).isNull();
            assertThat(TelegramParams.optionalLong(params("offset", " "), "offset")).isNull();
        }

        @Test
        void numbersAndNumericStrings() {
            assertThat(TelegramParams.optionalLong(params("offset", 7), "offset")).isEqualTo(7L);
            assertThat(TelegramParams.optionalLong(params("offset", " -3 "), "offset")).isEqualTo(-3L);
        }

        @Test
        void nonNumeric_namesTheKey() {
            assertThatThrownBy(() -> TelegramParams.optionalLong(params("offset", "1.5"), "offset"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'offset' must be a number");
        }
    }

    @Nested
    class UpdatesLimit {

        @Test
        void default_is100() {
            assertThat(TelegramParams.updatesLimit(params())).isEqualTo(100);
        }

        @Test
        void bounds_areInclusive() {
            assertThat(TelegramParams.updatesLimit(params("limit", 1))).isEqualTo(1);
            assertThat(TelegramParams.updatesLimit(params("limit", "100"))).isEqualTo(100);
        }

        @Test
        void outsideTheRange_isRejected() {
            for (Object bad : new Object[] {0, 101, "-5"}) {
                assertThatThrownBy(() -> TelegramParams.updatesLimit(params("limit", bad)))
                        .as(String.valueOf(bad))
                        .hasMessage("Parameter 'limit' must be between 1 and 100");
            }
        }

        @Test
        void nonNumeric_isRejectedAsNumber() {
            assertThatThrownBy(() -> TelegramParams.updatesLimit(params("limit", "ten")))
                    .hasMessage("Parameter 'limit' must be a number");
        }
    }
}
