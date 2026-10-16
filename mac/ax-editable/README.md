# Editable-selection check

Selection editing needs to know whether the selected text can be replaced. OpenWhispr's own probe answers "not editable" for any field that has a selection (it was written to check an empty cursor), so every edit went to the popup instead of replacing the text.

This helper checks the focused element of the app through Accessibility and prints `EDITABLE`, `NOT_EDITABLE` or `UNKNOWN`. Only `NOT_EDITABLE` (a web page, Finder) sends the result to the popup.

```sh
swiftc -O main.swift -o OWAXEditable
```

OpenWhispr runs it as a child process, so it uses OpenWhispr's Accessibility permission.
