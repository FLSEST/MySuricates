# Contributing

## Project status

MySuricates is a research project by a single developer, not a maintained
product. There is no support commitment and no promise of response times —
for issues and pull requests alike. See [README.md](README.md) for what the
project is and is not.

That is not a closed door. It is a description of the pace you should expect.

## Before you write code

**Open an issue first.** A pull request that arrives without a prior
conversation is likely to sit unread, or to be declined for reasons that would
have taken two sentences to explain up front. That is a waste of your time, and
avoiding it is the point of this paragraph.

Good first contributions are the small, verifiable kind: a broken link, a
documentation passage that no longer matches the code, a test that pins down
behaviour which is currently untested.

Use the bug report or idea issue form to start that conversation — they ask
for the details needed up front (version, installation method, reproduction
steps) instead of a second round of questions.

## Pull requests currently need prior agreement

Right now every pull request is discussed in an issue before it is opened. The
reason is deliberate and worth stating plainly: the project has exactly one
copyright holder today, and that keeps the option of relicensing open. Once
code by other people is merged, that option is gone without their consent. The
project may well stay on the AGPL-3.0 forever — but that should be a decision,
not something that happens by accident.

If this restriction is ever lifted, it will be lifted here.

## Sign your commits (DCO)

Contributions are accepted under the [Developer Certificate of
Origin](https://developercertificate.org/). It is a short statement that you
wrote the contribution, or otherwise have the right to submit it under the
project's license.

Sign off each commit:

```bash
git commit -s
```

which appends a line to the commit message:

    Signed-off-by: Your Name <your.email@example.com>

There is no separate CLA to sign and no bot. The sign-off also confirms the
licensing terms below.

## What a change has to satisfy

The same rules the maintainer works under — they are enforced by CI, not by
good intentions:

| Area | Command |
|---|---|
| Backend tests | `cd ai-automation-backend && mvn test` |
| New, moved or renamed classes | additionally `bash scripts/tests/arch-guard.sh` |
| Frontend types and lint | `cd ai-automation-frontend && npx tsc --noEmit && npm run lint` |
| Public hygiene gate | `bash scripts/ci/public-hygiene.sh --tracked` |

Further rules of the house:

- **Do not disable or delete a failing test** to make a change go green. Find
  the cause. A test that is wrong gets fixed with an argument for why it was
  wrong.
- **New behaviour needs a test.** A gate that does not measure is green.
- **German and English documentation are updated together.** `README.md` is
  generated from `README.de.md` — edit the German source, never the generated
  file. Its last line, `<!-- synced-from: <commit> sha256:<hash> -->`, names the version of
  the German file it reflects; `scripts/readme-sync/readme_sync.py` translates
  only the blocks changed since then and leaves every other byte alone.
- **Never access the SQLite database directly.** Not from code, not from shell
  blocks, not from scripts. See [CLAUDE.md](CLAUDE.md) for why.
- **Eight-character IDs** such as `TODO 1b84ecab` in commit messages, comments
  and documentation point to the maintainer's own task tracker. They are not
  issue numbers; the linked text usually says what the task was.

[docs/entwicklung.md](docs/entwicklung.md) has the full build and test
instructions.

## Security

Please do **not** report a suspected vulnerability as a public issue. Use
GitHub's private vulnerability reporting — the channel is described in
[SECURITY.md](SECURITY.md).

## Licensing of contributions

By contributing you agree that your contribution is licensed under the
[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0-only), the same
license that covers the project.

In addition, you grant the copyright holder of MySuricates (Florian Stampe) a
perpetual, worldwide, non-exclusive, royalty-free and irrevocable license to
use, reproduce, modify and distribute your contribution, and to license it to
others under different terms, including proprietary ones. You keep the
copyright in your contribution. This keeps the project's licensing in one hand,
the same reason pull requests currently need prior agreement.

---

## Kurzfassung (Deutsch)

MySuricates ist ein Forschungsprojekt eines einzelnen Entwicklers, kein
betreutes Produkt — kein Support, keine zugesagten Reaktionszeiten, auch nicht
auf Pull Requests.

- **Erst ein Issue, dann Code.** Ein Pull Request ohne vorheriges Gespräch
  bleibt wahrscheinlich liegen. Die Bug- oder Ideen-Vorlage beim Issue-Anlegen
  fragt die nötigen Angaben gleich ab.
- **Pull Requests brauchen derzeit vorherige Absprache.** Grund: Solange es nur
  einen Urheber gibt, bleibt eine spätere Umlizenzierung möglich. Mit fremdem
  Code im Bestand ginge das nur noch mit Zustimmung aller Beteiligten. Das soll
  eine Entscheidung sein, kein Versehen.
- **Commits signieren** nach dem [Developer Certificate of
  Origin](https://developercertificate.org/): `git commit -s`. Kein eigener CLA, kein Bot.
- **Tests müssen grün sein:** `mvn test` (bei neuen oder verschobenen Klassen
  zusätzlich `bash scripts/tests/arch-guard.sh`), `npx tsc --noEmit`,
  `npm run lint`, `bash scripts/ci/public-hygiene.sh --tracked`. Tests werden
  nicht abgeschaltet oder gelöscht, um eine Änderung grün zu bekommen.
- **Deutsch und Englisch zusammen pflegen.** `README.md` wird aus
  `README.de.md` erzeugt — die deutsche Quelle bearbeiten, nie die erzeugte Datei.
  Ihre letzte Zeile, `<!-- synced-from: <commit> sha256:<hash> -->`, nennt den Stand der
  deutschen Datei, den sie wiedergibt; `scripts/readme-sync/readme_sync.py`
  übersetzt nur die seitdem geänderten Blöcke und lässt jedes andere Byte stehen.
- **Sicherheitslücken** nicht als öffentliches Issue, sondern über den Meldeweg
  in [SECURITY.md](SECURITY.md).
- Beiträge stehen unter der [GNU AGPL v3.0](LICENSE). Zusätzlich räumst du dem
  Urheber (Florian Stampe) ein unbefristetes, nicht ausschließliches Recht ein,
  deinen Beitrag auch unter anderen Lizenzen weiterzugeben, einschließlich
  proprietärer. Dein Urheberrecht behältst du.
- **Achtstellige Kennungen** wie `TODO 1b84ecab` in Commits, Kommentaren und Doku
  verweisen auf den Aufgaben-Tracker des Maintainers, nicht auf Issues.
