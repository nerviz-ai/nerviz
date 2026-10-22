# 0181 · The repository moves from MIT to Apache 2.0, with a NOTICE, a trademark policy and a DCO

- **Date:** 2026-10-22
- **Scenario:** Preparing for enterprise adoption and a possible paid plan. The repository was MIT,
  with no patent grant, no statement on the name and logo, and no attribution file for the
  Apache-licensed OpenRewrite it invokes.
- **Decision:** Option 1 — relicense to Apache License 2.0, add `NOTICE` and `TRADEMARK.md`, ask
  for a DCO sign-off, and have `export` carry `LICENSE` and `NOTICE` into `.claude/` of a
  generated project.
- **State:** approved by Lucas Fernandes, on 2026-10-22

## Facts

| # | Fact | Evidence |
|---|---|---|
| 1 | Sole copyright holder: every commit is by one person (`git shortlog -sne`), so relicensing needs no consent from anyone else | 50 commits, one email |
| 2 | The previous `LICENSE` was MIT, README badge and `## License` said MIT | `LICENSE`, `README.md` |
| 3 | `export` writes `.claude/` into third-party projects, i.e. redistributes this work | `extensions.json` `export` |
| 4 | `brand/` carried no statement; under MIT the logos were licensed with the code | `brand/` |

## Options

| Option | Verdict | Why |
|---|---|---|
| 1 — Apache 2.0, `NOTICE`, `TRADEMARK.md`, DCO | **chosen** | Explicit patent grant, § 6 keeps names out of the license, § 5 makes contributions inbound = outbound (no CLA), `NOTICE` is the license's own mechanism, and the ecosystem (Spring, OpenRewrite) is Apache |
| 2 — keep MIT | rejected | Silent on patents and trademarks; enterprise legal reviews ask for both |
| 3 — MIT OR Apache-2.0 dual | rejected | Two texts to keep and ship for no gain here |
| 4 — AGPL, BSL, source-available | rejected | Would be read as contaminating or restricting the generated project, in the phase where adoption matters most |

## Consequences

- **Timing was the argument.** Relicensing is free while there is a single holder; with external
  contributors it needs each one's consent. Anything contributed from now on is Apache 2.0.
- **Versions already published** under MIT stay MIT for whoever received them; nothing is revoked.
- **The generated project's own license is its owner's.** `export` writes `LICENSE` and `NOTICE`
  to `.claude/`, never to the root. A project that redistributes `.claude/` keeps both there.
- **Open core stays possible.** If a paid edition comes, it lives outside this repository under
  its own terms; this one remains Apache 2.0. That is a separate decision, not taken here.
- **DCO is requested, not enforced.** No CI check was added; a check against a failure nobody
  observed is the process invariant 6 warns about. Add one when an unsigned commit is seen.
- **Not legal advice.** A trademark search and filing (INPI classes 9 and 42) and a review of
  `TRADEMARK.md` by an IP lawyer come before the paid plan.
