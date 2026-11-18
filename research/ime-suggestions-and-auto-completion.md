# Android IME Suggestions and Auto-Correction in Terminal Emulators

## Problem Statement

When using software keyboards (such as Gboard, Samsung Keyboard, or SwiftKey) inside mobile terminal emulators, the system IME defaults to analyzing typed Latin/English text using natural language dictionaries. This results in:
1. Unwanted auto-corrections (e.g., changing `git` to `got`, `grep` to `grow`, or altering Unix paths and options).
2. Word suggestion bar popups and auto-completions that insert unrequested words or auto-capitalize flags.
3. Multi-word context buffering (`setInitialSurroundingText`), where typing backspace or space causes the keyboard to delete or replace characters across previous terminal lines.

## Why `TYPE_TEXT_FLAG_NO_SUGGESTIONS` Fails on Modern Keyboards

On Android, Gboard and other vendor keyboards have a documented behavior where `InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS` on standard `InputType.TYPE_CLASS_TEXT` is treated merely as a weak hint. If the user has global spell-check or suggestion strips turned on in their keyboard app settings, Gboard completely bypasses `TYPE_TEXT_FLAG_NO_SUGGESTIONS` and continues suggesting and auto-replacing English words.

Furthermore, when toggling preferences from a `ModalBottomSheet` dialog, the underlying `TerminalView` does not hold window focus (`hasFocus() == false`). Any naive calls to `InputMethodManager.restartInput()` during preference changes are silently dropped by the Android input subsystem, leaving the existing input connection active until the view explicitly handles focus return.

## Technical Solutions Implemented in Rexling

### 1. `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` Enforced Suppression

To unconditionally compel Gboard and all third-party IMEs to drop word prediction models, dictionary lookups, and auto-corrections, Rexling mirrors the strategy tested and validated in Termux:
```kotlin
outAttrs.inputType = if (keyboardSuggestions) {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
} else {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}
```
Key properties:
- `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`: Mandates that the IME suppress dictionary suggestion bars and auto-corrections entirely.
- Characters remain visible and unmasked (unlike `TYPE_TEXT_VARIATION_PASSWORD`).
- `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`: Prevents the IME from storing typed passwords or terminal commands in the user's personal dictionary.

### 2. Suppressing Surrounding Text Context

When `keyboardSuggestions == false`, `outAttrs.setInitialSurroundingText(editor)` is omitted. This ensures that the IME does not parse earlier command lines or shell prompts to seed its next-token prediction model.

### 3. Reliable Input Re-initialization across Focus Handoff

In `TerminalView`:
- If `keyboardSuggestions` is modified while `TerminalView` is not focused (e.g., while the user is inside `PreferencesSheet`), `imeDirty` is flagged.
- When `PreferencesSheet` is dismissed and `TerminalView.onWindowFocusChanged(hasWindowFocus = true)` fires, if `imeDirty` is set, `InputMethodManager.restartInput(this)` is invoked, ensuring the soft keyboard refreshes with the updated `InputType` immediately.
