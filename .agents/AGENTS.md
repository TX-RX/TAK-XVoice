## Logging Guidelines

1. **Do not** write logcat dumps, trace files, crash logs, or any ad-hoc diagnostic files to the root directory of the repository.
2. Always write these diagnostic files to the .logs/ directory (which is explicitly ignored via .gitignore).
3. Before writing any files, check .gitignore to ensure the target directory is properly excluded to prevent dirtying the git workspace.
