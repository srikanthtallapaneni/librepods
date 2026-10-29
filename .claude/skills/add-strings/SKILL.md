---
name: add-strings
description: Add, rename or translate user-facing strings in the LibrePods Android app (res/values*/strings.xml) without breaking the 13 locales. Use whenever UI text changes.
---

# Add or change strings

Base file: `android/app/src/main/res/values/strings.xml` (English, root element has
`tools:ignore="MissingTranslation"`, so missing translations are allowed).

Locales present: `values-de`, `-es`, `-fr`, `-ja`, `-ko`, `-pt`, `-tr`, `-uk`, `-vi`, `-zh-rCN`,
`-zh-rTW`, plus `value-it` (misnamed; Android ignores it until renamed to `values-it`).

Rules:
1. Add new keys to `values/strings.xml` only, `snake_case`, grouped near related keys. Escape
   apostrophes (`\'`). Mark brand names or format-only strings `translatable="false"`.
2. Use `stringResource(R.string.key)` in Compose, `context.getString(R.string.key)` elsewhere.
   Use `%1$s`/`%1$d` placeholders, never string concatenation for sentences.
3. Don't add machine-translated or English text to locale files. If the user supplies a
   translation, add it to that locale's `strings.xml` under the same key.
4. **Renaming a key**: rename it in every `values*/strings.xml` and `value-it` file and all Kotlin
   usages (`grep -rn "R.string.old_key" android/app/src`), so no locale keeps an orphan.
5. **Removing a key**: remove it from all locale files too, after checking it is unused
   (including `res/xml` and `res/layout` widget files).
6. Wording follows current upstream terms: "Listening Mode" (not "noise control"),
   "Conversation Awareness" (not "conversational").
7. Don't commit. Leave the change for Sri and suggest the subject `android: update <lang> translations`
   / `android: add <lang> translation`.
