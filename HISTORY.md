# Repository history

Rexling (formerly Rexlink) combines the original `rex-probe` and `rex-android` Git histories.
The migration retains the original commits and their IDs; it does not squash or
filter-rewrite either source history. The merge commit connects both ancestries.
Source-head IDs and migration details are recorded in `MIGRATION.json`.

- `legacy/probe` identifies the imported probe history.
- `legacy/android` identifies the imported Android history.
- New commits record pending Android work, consolidation, build-path updates,
  documentation and Apache 2.0 licensing separately.
- Original directories remain intact as a rollback/reference until you choose
  to retire them. The new build has no dependency on either old checkout.

```sh
git log --all --graph --decorate --oneline
git log legacy/android --oneline
git log legacy/probe --oneline
git log -m --follow -- rexling-core/src/control.rs
git log -m --follow -- rexling-android/app/src/main/java/dev/lemon/rex/MainActivity.kt
```

Use `-m` to examine the import merge against each parent when following file
renames into the original roots. Git follows individual file renames heuristically. The explicit legacy branches
always expose full pre-migration history even if a directory-level log does not
cross the merge automatically. There are no submodules or nested `.git` repos.
Nothing was pushed to a remote service by this migration.

## Rexling rename — 2026-11-05

The project and GitHub repository were renamed to Rexling. Source projects moved
to `rexling-android/` and `rexling-core/`; Rust packages are `rexling-core` and
`rexling-jni`. These are ordinary file renames; existing commits and source-head
IDs are retained. Earlier evidence and `MIGRATION.json` retain their original
paths and project names. The repository is now https://github.com/shulhi/rexling.
Android keeps its installed app ID and JNI ABI so updates preserve app data.
