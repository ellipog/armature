# Text models

The editing model and the widget are two different objects, and the split is the point: everything
worth being wrong about in a text field is arithmetic on a string — where the caret goes on Backspace
at a line's start, which character a click at x pixels means, what a selection contains after a
paste — and arithmetic on a string is the one thing a test can hold without a client, a font or a
window. So `TextField` and `TextArea` are game-free, and `ArmatureTextField` and `ArmatureTextArea`
are the widgets that draw and key them.

## `TextField` and `TextArea`

```java
TextField field = TextField.of(64).setValue("my_pack");
field.insert(':');                 // typing
field.backspace();                 // deleting
field.left();                      // the caret moves one code point
field.selectAll().copyText();
```

Both answer `value()`, `caret()`, `length()`, `isEmpty()`, `hasSelection()`, `selectionStart()`,
`selectionEnd()` and `selectedText()`, and both offer `selectAll()`, `selectTo(position)` and
`selectWordAt(position)` (the field), with `copyText()`, `cutText()` and `pasteText(text)`.
`TextArea` adds the line dimension — a newline moves the caret to the next line's start — through the
same operations. There is no shift-arrow: a selection is made by dragging or by `selectAll`, and the
field's own keys are Left, Right, Home, End, Backspace and Delete, with Ctrl+C, Ctrl+V and Ctrl+X for
the clipboard.

Three pieces underneath, each written once:

- **`Codepoints`** — what counts as one character to a caret, a mark and a deletion: a code point, one
  `char` for everything in the basic plane and two for everything outside it. A caret that walks
  `char` by `char` splits emoji in half; this is why it does not.
- **`TextHistory`** — undo history as whole states rather than changes, so an undo cannot leave a
  selection pointing into text that no longer exists.
- **`TextWrap`** — a paragraph into lines that fit a column, collapsing runs of spaces the way the
  card's prose has always been shown.

## `RichText`: a description's markdown

`RichText.parse(markdown)` turns a description into the blocks that are drawn and the styled runs over
their visible text:

```java
List<RichText.Paragraph> paragraphs = RichText.parse(markdown);
for (RichText.Paragraph p : paragraphs) {
    float scale = RichText.scale(p);            // heading sizes; body text is 1.0
    for (RichText.Line line : RichText.wrap(p, width, measure)) {
        List<RichText.Piece> pieces = RichText.pieces(p, line);
        ...
    }
}
```

- **Blocks**: a paragraph, a heading (a line that began with `#`s, `level` says how many), or a bullet
  (a line that began with `- `, `* ` or `+ `).
- **Inline**, one level: `**bold**`, `*italic*` / `_italic_`, `` `code` ``, `[text](url)`. Markup
  inside a styled span is shown literally rather than interpreted — a real inline grammar is a much
  larger promise to keep. A `\` escapes the punctuation that would otherwise be markup, and an unmatched delimiter is left as
  itself: a description with one stray `*` should read as prose, not as a parse error.
- **A line break is a line break.** Markdown here does not join lines into flowing paragraphs, because
  these files are written a line per paragraph and joining them would re-wrap prose an author had
  already wrapped.

The visible text — markers removed, whitespace normalised — is what everything else works on: the
runs index *it*, the layout measures it, and a link rectangle is cut from it. The source string is the
author's and is never rewritten; the editor edits the markdown itself, and this is only how it is
read.
