---
name: pr
description: Land the current branch's open pull request in app-devper/MediaPlayer: check the landing gates (CI green, review findings answered, approval covers the head commit), wait for the required check, squash-merge, delete the branch, sync the base branch, and back-merge main into develop when the PR targeted main. Use when the user says "pr land", "land", "merge it", "/pr", or otherwise asks to land the current PR.
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

2. **Landing gates** (the rules are in `dev-flow` stages 6–7). Check them
   before anything is pushed; stop and report if one fails:
   - **Review answered:** every finding from the tier's reviews is fixed or
     closed with a reason. An open Important finding blocks.
   - **Approval covers the head commit:** find the last "Approved by the
     author at <sha>" comment (`gh pr view <n> --comments`) and list what
     came after it, local commits included (`git log <sha>..HEAD --oneline`).
     Anything listed is shown to the user and confirmed; "land" right after
     seeing the list counts. No such comment → ask the user to approve the
     current head and record it.
   - **Revert path:** the PR body says how to revert.

3. **Push the confirmed commits**
   ```bash
   git push
   ```

4. **Check mergeability.** `CONFLICTING` → stop and report; do **not**
   force. `UNKNOWN` → re-run step 1 once before proceeding.

5. **Wait for CI, then squash-merge.** Auto-merge is disabled on this repo,
   so wait for the required check and merge yourself:
   ```bash
   gh pr checks <number> --required --watch --interval 30   # in the background; you're notified when it ends
   gh pr checks <number> --required                         # "Linux (JVM + Android)" pass?
   gh pr merge <number> --squash --delete-branch
   ```
   - Required check failed → stop, do not merge, and show the failing step:
     `gh run list --branch <headRefName> -L 1 --json databaseId -q '.[0].databaseId'`
     gives the run id for `gh run view <id> --log-failed`.
   - A non-required check failing doesn't block; mention it.

6. **Sync the base branch and clean up**
   ```bash
   git checkout <baseRefName>
   git pull --ff-only origin <baseRefName>
   git branch -D <headRefName>
   git remote prune origin
   ```

7. **If the PR targeted `main`: back-merge into `develop`** (mandatory;
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

8. **Report**: merged PR number + URL, the squash commit SHA on the base
   branch (`git log --oneline -1`), the branch deleted locally and on the
   remote, and for `main` landings the back-merge result.

## Notes

- Default strategy is `--squash`. Use `--merge` / `--rebase` only if the
  user explicitly asks (squash is the only method enabled on GitHub).
- A merge into `main` is a release. Before landing, confirm the
  release/hotfix PR bumped the version everywhere (`dev-flow` stage 8.3).
- Run from inside `/Users/admin/ProjectPos/MediaPlayer`.
