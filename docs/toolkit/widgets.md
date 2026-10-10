# Widgets and screens

The controls the kit ships are ordinary `AbstractWidget`s, so focus, narration, tab order and hover
all work the way the game already makes them work. What the kit adds is that they draw through the
renderer [[seam|seam]] and take their colours from the palette.

## Controls

```java
ArmatureButton claim = new ArmatureButton(x, y, 90, 20, Component.literal("Claim"), this::claim);
claim.accent(true);                 // the one control a panel is steering towards
claim.borderless(true);             // for a row that is already inside a box
```

`ArmatureButton` also has `selected(...)` and `flat(...)`, so one class covers the loud button, the
quiet one and the row that behaves like a choice. A button's picture need not be an item:
`texture(id)` draws a texture file before the label and `sprite(id)` an atlas region, the item
winning when set beside either — one icon arm per control — and `iconInset` says how far the
sprite sits inside the edge.

`ArmatureTextField` and `ArmatureTextArea` are the editing widgets; the models under them are on
[[armature:toolkit/text]].

```java
ArmatureTextField name = new ArmatureTextField(x, y, width, 18, "my_pack");
name.onSubmit(this::commitName);
```

## `ArmatureScreen`: a panel that follows its data

Extend `ArmatureScreen` instead of `Screen` and a panel updates itself when the state behind it moves:

```java
public final class MyPanel extends ArmatureScreen {

    public MyPanel() { super(Component.literal("My panel")); }

    @Override
    protected void renderContent(GuiRenderer r, int mouseX, int mouseY, float partialTick) {
        // Everything this panel draws, through the seam.
    }

    @Override
    protected void rebuildWidgets() {
        // Recreate the controls for the current state.
    }
}
```

Three things come with the base class:

- **`renderContent` is the drawing hook.** The game's graphics type is wrapped once here, so a
  subclass draws through `GuiRenderer` and never names the type underneath. Drawing happens before the
  widget pass, which is the order a panel wants: background, then controls.
- **Vanilla's background is suppressed** — no blur, no menu gradient. A panel that draws its own card
  would otherwise get the game's fade landing *between* its drawn half and its widget half, which is
  invisible in the code and obvious on screen. A subclass that wants the vanilla background back
  overrides `renderBackground` again.
- **A watched value moving calls `rebuildWidgets()` for you** — see below.

> [!NOTE]
> A screen that has to interleave its own drawing with the widget pass — drawing an item icon at a
> raised Z, say — cannot use `renderContent` and overrides `render` instead. That is the one place a
> screen is expected to name the game's type, and it says why in the seam allow-list.

## `ArmatureLive`: what a screen watches

The problem this solves is small and universal: a screen is built once, while the things it describes
— a party's members, saved progress, a reward waiting — change underneath it. Each panel that
grows its own "has anything changed" check is a panel that can forget one.

So the data registers itself, once, where it lives:

```java
ArmatureLive.watch("party", () -> MyState.partyRevision());
ArmatureLive.watch("rewards", () -> MyState.rewardRevision());
```

Every `ArmatureScreen` checks those numbers once per frame; when one has moved, it rebuilds — every
screen, including screens written later, with nothing to remember and no field to add. `revisions()`
and `watched()` are there for a diagnostic screen that wants to show the numbers.

**The check is in the frame rather than on the message that changes the data**, deliberately: a
message handler runs on whichever thread received it, at whatever moment, possibly while the screen is
half-drawn — and a widget list rebuilt from a network thread is a crash rather than a redraw. The
frame loop is the one place always on the right thread and never in the middle of anything. The cost
is a comparison of a few numbers per frame.

> [!TIP]
> A rebuild does not throw the reader's place away: a `ScrollView`'s offset lives in the kit's own
> object, and `apply` clamps it against the new layout rather than resetting it. A watched value
> moving under a scrolled-down reader redraws the row and leaves the column where it was.
