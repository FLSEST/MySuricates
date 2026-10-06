import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('german_comments', ROOT / 'scripts/tools/german_comments.py')
german_comments = importlib.util.module_from_spec(spec)
spec.loader.exec_module(german_comments)


class GermanCommentRatchetTest(unittest.TestCase):
    """Code comments are English; files with German comments may only shrink (TODO 9468432d)."""

    def test_no_file_exceeds_its_ceiling(self):
        self.assertEqual(0, german_comments.ratchet(update=False))

    def test_heuristic_flags_umlauts_and_german_function_words(self):
        self.assertTrue(german_comments.is_german('Prüft den Lauf'))
        self.assertTrue(german_comments.is_german('wird nicht gestartet'))
        self.assertFalse(german_comments.is_german('Checks the run before it starts'))
        self.assertFalse(german_comments.is_german('die if the process is gone'))

    def test_comments_and_strings_are_told_apart(self):
        code, comments = german_comments.lex(Path('A.java'), 'String s = "// kein Kommentar"; // echter Kommentar\n')
        self.assertIn('"// kein Kommentar"', code)
        self.assertEqual([(1, ' echter Kommentar')], comments)
        code, comments = german_comments.lex(Path('a.sh'), 'echo "# Ausgabe" # Kommentar\ncat <<EOF\n# Daten\nEOF\n')
        self.assertEqual([(1, ' Kommentar')], comments)
        self.assertIn('# Daten', code)

    def test_test_descriptions_count_but_assertion_strings_do_not(self):
        _, comments = german_comments.lex(Path('a.test.ts'), "it('zeigt den Fehler', () => expect(x).toBe('Fehler'))\n")
        self.assertEqual([(1, 'zeigt den Fehler')], comments)

    def test_jsx_closing_tag_keeps_line_numbers(self):
        _, comments = german_comments.lex(Path('a.tsx'), 'const a = <div>\n</div>;\n// dritte Zeile\n')
        self.assertEqual([(3, ' dritte Zeile')], comments)


if __name__ == '__main__':
    unittest.main()
