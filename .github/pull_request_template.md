## What and why

<!-- What does this change, and why? Link the issue it was discussed in. -->

## Checklist

- [ ] This was discussed and agreed in issue #... beforehand (see
      [CONTRIBUTING.md](https://github.com/FLSEST/MySuricates/blob/main/CONTRIBUTING.md) —
      pull requests currently need prior agreement; the project has a single copyright holder
      and that keeps the option of relicensing open)
- [ ] I understand this contribution is licensed under
      [AGPL-3.0](https://github.com/FLSEST/MySuricates/blob/main/LICENSE), the same
      license that covers the project, with the additional relicensing grant to the copyright
      holder described in CONTRIBUTING.md
- [ ] `cd ai-automation-backend && mvn test` passes
- [ ] New, moved, or renamed classes: `bash scripts/tests/arch-guard.sh` passes too
- [ ] `cd ai-automation-frontend && npx tsc --noEmit && npm run lint` passes
- [ ] `bash scripts/ci/public-hygiene.sh --tracked` passes
- [ ] German and English documentation were updated together, where affected
      (`README.de.md` is the source, never edit the generated `README.md` directly)
