---
name: git-flow
description: Git flow branching for app-devper/MediaPlayer: main (released builds, tagged by hand after each merge), develop (default, integration), feature/*, release/*, hotfix/*. Use when starting new work, opening a PR, cutting a release, hotfixing a shipped build, or deciding which base branch a change targets.
---

# git-flow: branching + release model for this repo

The stage-by-stage flow (shape → build → review → release) lives in
`dev-flow`; this skill owns the branch rules.

## Branch map

| Branch | Role | PR target | After merge |
|---|---|---|---|
| `main` | released builds; tag `v<app-version>` by hand after every merge | — | back-merge into `develop` |
| `develop` | default branch, integration | — | — |
| `feature/<x>` | new work and fixes for the next release | `develop` | delete branch |
| `release/<x.y.z>` | release cut from `develop` | `main` | back-merge `main` → `develop`, delete branch |
| `hotfix/<x>` | urgent fix to a shipped build, from `main` | `main` | back-merge `main` → `develop`, delete branch |

Both `main` and `develop` require the check **"Linux (JVM + Android)"**
(`.github/workflows/check.yml`). PRs land via **squash** (the only merge
method enabled); head branches are deleted on merge.

CI runs on Linux and does **not** compile iOS. Run the iOS check from
`dev-flow` stage 5 yourself before opening a PR.

## What merging `main` does: nothing automatic

There is no deploy pipeline. Merging `main` neither builds, tags nor publishes.
After a release/hotfix lands, tag `v<app-version>` and build the artifacts by
hand, then publish them as a GitHub release and, for Android and iOS, to
Firebase App Distribution (`dev-flow` stage 8). Every `release/*` or `hotfix/*` PR
must bump the version in all places listed in `dev-flow` stage 8.3, so the
tag and the shipped apps agree.

## Recipes

### Feature

```bash
git checkout develop && git pull --ff-only
git checkout -b feature/<slug>
# work, commit
git push -u origin feature/<slug>
gh pr create --base develop --title "..." --body "..."
```

### Release

```bash
git checkout develop && git pull --ff-only
git checkout -b release/<x.y.z>
# bump the version (dev-flow stage 8.3)
gh pr create --base main --title "release: v<x.y.z>" --body "..."
```

### Hotfix

```bash
git checkout main && git pull --ff-only
git checkout -b hotfix/<slug>
# fix + patch version bump
gh pr create --base main --title "fix(...): ..." --body "..."
```

### Back-merge (required after every merge into main)

The back-merge is the only direct push to `develop`, in every app-devper repo.
It needs an account with admin rights: `develop` requires the CI status check
and a pushed merge commit has none, so GitHub answers
`Bypassed rule violations for refs/heads/develop`, which is expected here.
The `check` workflow still runs on the push; confirm it is green:
`gh run list --branch develop -L 1`. Never squash a back-merge (it would drop
`main` from `develop`'s history), and don't open a PR for it.

```bash
git checkout main && git pull --ff-only
git checkout develop && git pull --ff-only
git merge main            # usually fast-forward; resolve if develop moved
git push origin develop
```

## Guard rails

- Never push directly to `main` or `develop`; always go through a PR. The
  one exception is the back-merge `main` → `develop` (see Back-merge).
- Never open a feature PR against `main`; only `release/*` and `hotfix/*`
  target `main`.
- Never force-push `main` or `develop`. History was rewritten once
  (2026-10-16) to purge a leaked keystore. Don't repeat that unless secrets
  leak again, and if they do, rotate them as well.
- Landing PRs (merge + branch cleanup + sync) is the **pr** skill's job:
  invoke `/pr` rather than hand-rolling the merge.
