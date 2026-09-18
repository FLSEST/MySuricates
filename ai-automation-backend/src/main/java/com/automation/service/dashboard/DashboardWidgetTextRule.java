package com.automation.service.dashboard;

import java.util.Set;

/**
 * Whether a single code point of a contract string (fix round 2, B1+B2) is fatal, and whether it
 * counts toward "the string has visible content". Split out of {@link DashboardWidgetResultParser}
 * to keep that file under the source-size ratchet — see its class javadoc for the two-tier "fatal"
 * vs. "not visible" rule this implements and why the two are different on purpose.
 */
final class DashboardWidgetTextRule {

    /**
     * Filler code points that are individually legal Unicode (not control/format/separator) but
     * render as blank: HANGUL FILLER and its choseong/jungseong siblings, HALFWIDTH HANGUL FILLER,
     * and BRAILLE PATTERN BLANK.
     */
    private static final Set<Integer> INVISIBLE_FILLERS = Set.of(0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0);

    private DashboardWidgetTextRule() {
    }

    /**
     * Control characters, invisible format characters (zero width, BOM, bidi overrides), line and
     * paragraph separators, lone surrogates and private-use code points — fatal wherever they occur,
     * regardless of what else is in the string.
     */
    static boolean isFatal(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                || type == Character.PRIVATE_USE;
    }

    /**
     * True for a code point that counts toward "the string has visible content". A normal space
     * (U+0020) is legal but does not itself count — nor does every {@code SPACE_SEPARATOR} other than
     * U+0020, the invisible filler letters/symbols, the non-Cf default-ignorable ranges, or an
     * unassigned code point.
     */
    static boolean isVisible(int codePoint) {
        if (codePoint == ' ') {
            return false;
        }
        if (INVISIBLE_FILLERS.contains(codePoint) || isNonFormatDefaultIgnorable(codePoint)) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.SPACE_SEPARATOR && type != Character.UNASSIGNED;
    }

    /**
     * Non-{@code Cf} default-ignorable code points: individually legal Unicode (category Mn) that
     * render as nothing on their own — a bare combining grapheme joiner, a lone variation selector,
     * or a Mongolian/Khmer vowel-shaping mark without its base. Named as explicit ranges because the
     * JDK exposes no direct "Default_Ignorable_Code_Point" property lookup.
     */
    private static boolean isNonFormatDefaultIgnorable(int codePoint) {
        return codePoint == 0x034F
                || (codePoint >= 0x17B4 && codePoint <= 0x17B5)
                || (codePoint >= 0x180B && codePoint <= 0x180F)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }
}
