---
name: pr
description: Land the current branch's open pull request in app-devper/MediaPlayer: squash-merge (auto-merge if the required check is still running), delete the branch, sync the base branch, and back-merge main into develop when the PR targeted main. Use when the user says "pr land", "merge it", "/pr", or otherwise asks to land the current PR.
---

# pr: land the current branch's PR

This repo follows **git flow** (see the `git-flow` skill): feature PRs target
`develop`; release/hotfix PRs target `main`. Both branches require the
"Linux (JVM + Android)" check. PRs land via **squash**.

## Steps

1. **Find the PR for the current branch**
   ```bash
   gh pr view --json number,state,mergeable,mergeStateStatus,baseRefName,headRefName,title
   ```
   - No PR for this branch → stop and tell the user (offer `gh pr create`
     with the base the `git-flow` skill prescribes).
   - `state` is `MERGED`/`CLOSED` → report it; nothing to do.

2. **Push any unpushed commits first**
   ```bash
   git push
   ```

3. **Check mergeability.** `CONFLICTING` → stop and report; do **not**
   force. `UNKNOWN` → re-run step 1 once before proceeding.

4. **Squash-merge**
   ```bash
   gh pr checks <number>
   ```
   - Required check passed → `gh pr merge <number> --squash --delete-branch`
   - Still running → `gh pr merge <number> --squash --auto --delete-branch`
     then watch until it lands:
     ```bash
     gh pr view <number> --json state -q .state   # poll / Monitor until MERGED
     ```
   - Check failed → stop, show the failure, do not merge.

5. **Sync the base branch and clean up**
   ```bash
   git checkout <baseRefName>
   git pull --ff-only origin <baseRefName>
   git branch -D <headRefName>
   git remote prune origin
   ```

6. **If the PR targeted `main`: back-merge into `develop`** (mandatory;
   see the `git-flow` skill)
   ```bash
   git checkout develop && git pull --ff-only origin develop
   git merge main && git push origin develop
   ```
   This is pushed directly on purpose (admin only; GitHub reports
   "Bypassed rule violations", which is expected). Then check that
   `gh run list --branch develop -L 1` is green.
   Nothing is tagged or published automatically. Remind the user to tag
   `v<app-version>` and build the release (`dev-flow` stage 8, steps 6–8).

7. **Report**: merged PR number + URL, the squash commit SHA on the base
   branch (`git log --oneline -1`), the branch deleted locally and on the
   remote, and for `main` landings the back-merge result.

## Notes

- Default strategy is `--squash`. Use `--merge` / `--rebase` only if the
  user explicitly asks (squash is the only method enabled on GitHub).
- A merge into `main` is a release. Before landing, confirm the
  release/hotfix PR bumped the version everywhere (`dev-flow` stage 8.3).
- Run from inside `/Users/admin/ProjectPos/MediaPlayer`.
