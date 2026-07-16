# Clean up project structure and update .gitignore

The project root contains several redundant or local-only directories (`.freebuff/`, `.kotlin/`) and is currently versioning local IDE settings in `.idea/`. This plan aims to clean up these files and establish a robust `.gitignore` to prevent future noise.

## User Review Required

> [!WARNING]
> **Deleting Folders**: I will delete `.freebuff/` and `.kotlin/` as they contain local cache and logs.
>
> **Untracking .idea/**: I will stop tracking the `.idea/` folder. This means your personal IDE settings (like which files you have open or your local Gradle path) will no longer be pushed to GitHub. This is standard practice for collaborative projects.

## Proposed Changes

### Root Cleanup

#### [DELETE] [.freebuff/](file:///C:/Users/glen/StudioProjects/3mail/.freebuff)
- Local tool cache/database. Not needed in the repo.

#### [DELETE] [.kotlin/](file:///C:/Users/glen/StudioProjects/3mail/.kotlin)
- Kotlin compiler logs and session data. Not needed in the repo.

#### [MODIFY] [.gitignore](file:///C:/Users/glen/StudioProjects/3mail/.gitignore)
- Update with a comprehensive list of standard Android, Gradle, and OS ignores.
- Explicitly add `.freebuff/` and `.kotlin/`.
- Ensure `.idea/` is fully ignored.

### Git Maintenance

- **Untrack .idea/**: Run `git rm -r --cached .idea/` to stop versioning these files.
- **Untrack .freebuff/ & .kotlin/**: Ensure they are removed from the index.

## Verification Plan

### Manual Verification
- Check `git status` after changes to ensure only `.gitignore` is modified and the redundant folders are gone.
- Verify the app still builds and runs correctly in Android Studio.
