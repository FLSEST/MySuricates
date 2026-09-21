package com.automation.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderSyntaxTest {

    @Test
    void nullAndEmpty_haveNoKeys() {
        assertThat(PlaceholderSyntax.keysIn(null)).isEmpty();
        assertThat(PlaceholderSyntax.keysIn("")).isEmpty();
    }

    @Test
    void bothSpellings_inOrder_withRepetitions() {
        assertThat(PlaceholderSyntax.keysIn("{{a}} {{ b }} {{a}}")).containsExactly("a", "b", "a");
    }

    @Test
    void keysMayContainSpacesAndDots() {
        assertThat(PlaceholderSyntax.keysIn("{{ block.Build Report.output }}"))
                .containsExactly("block.Build Report.output");
    }

    @Test
    void asymmetricSpacing_yieldsKeyWithSurroundingSpace() {
        assertThat(PlaceholderSyntax.keysIn("{{ a}}")).containsExactly(" a");
        assertThat(PlaceholderSyntax.keysIn("{{a }}")).containsExactly("a ");
    }

    @Test
    void emptyBracesAndNestedBraces_areNotPlaceholders() {
        assertThat(PlaceholderSyntax.keysIn("{{}} { x } {{ a{b }}")).isEmpty();
    }
}
