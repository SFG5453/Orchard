# Contributing

Keep changes focused and run the checks relevant to your changes. See [Development](docs/DEVELOPMENT.md) for build commands

## File limits

Keep each file under 500 lines. Meson files are exempt. If you edit a file that already exceeds the limit, split it into smaller files or make a small module for your changes.

## Comments

dont flood code files with comments or emdashes lol

Keep comments short and useful. Explain non-obvious intent, invariants, constraints, or unusual edge cases. Avoid restating the code.

## No historical narration

Describe the code as it exists now. (looking at you vibe coders...)

Avoid wording such as:

- "used to"
- "previously"
- "formerly"
- "now"
- "before this change"
- "this was changed to"
- "we switched from"
- "instead of the old implementation"

Do not leave comments describing implementation history. Git records implementation history.
