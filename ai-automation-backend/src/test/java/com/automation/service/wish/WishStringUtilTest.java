package com.automation.service.wish;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WishStringUtilTest {

    @Nested
    class TruncateTests {

        @Test
        void nullInput_returnsEmptyString() {
            assertThat(WishStringUtil.truncate(null, 10)).isEqualTo("");
        }

        @Test
        void emptyString_returnsEmpty() {
            assertThat(WishStringUtil.truncate("", 10)).isEqualTo("");
        }

        @Test
        void shorterThanMax_returnsOriginal() {
            assertThat(WishStringUtil.truncate("abc", 10)).isEqualTo("abc");
        }

        @Test
        void exactlyMax_returnsOriginal() {
            assertThat(WishStringUtil.truncate("abcde", 5)).isEqualTo("abcde");
        }

        @Test
        void longerThanMax_truncatesWithEllipsis() {
            assertThat(WishStringUtil.truncate("abcdefgh", 5)).isEqualTo("abcde...");
        }

        @Test
        void maxZero_truncatesWithEllipsis() {
            assertThat(WishStringUtil.truncate("abc", 0)).isEqualTo("...");
        }
    }
}
