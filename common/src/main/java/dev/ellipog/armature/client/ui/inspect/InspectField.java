package dev.ellipog.armature.client.ui.inspect;

import java.util.Objects;

/**
 * One typed field of a property panel: how a value is shown as text, and how typed text becomes a value.
 *
 * <h2>Why the type rules are a thing and not a switch in a screen</h2>
 *
 * <p>Because the rules are where the honest failures live. A field that accepts "twenty" for a count and
 * commits it as zero is not a drawing fault, an arithmetic fault, or something a screenshot can show --
 * it is a rule, and a rule is a thing a test can hold. So the parse is here, game-free, and the widget
 * is the ordinary text field: the field's submit hands the text to {@link #parse}, an ok commits, and an
 * error is shown and not committed, which is the whole of "per field, on commit" for a typed value.
 *
 * <p>The formatting half exists so the round trip is honest: what a panel shows for a value is produced
 * by the same class that reads a panel's text back, so the two cannot drift into showing one format and
 * accepting another.
 *
 * @param <T> the value's type, as the caller's object holds it
 */
public interface InspectField<T> {

    /** The name the row shows. */
    String label();

    /** The value as text, for the field to start from. Never null. */
    String format(T value);

    /** Typed text as a value, or the reason it is not one. */
    Result<T> parse(String text);

    /** A parse's answer: the value, or the message that says why there is none. Exactly one is set. */
    record Result<T>(T value, String error) {

        public static <T> Result<T> ok(T value) {
            Objects.requireNonNull(value, "value -- a refusal says so with error, not with null");
            return new Result<>(value, null);
        }

        public static <T> Result<T> bad(String error) {
            Objects.requireNonNull(error, "error");
            return new Result<>(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Any text, as it is typed. The empty string is the value being absent, which is a caller's call. */
    static InspectField<String> text(String label) {
        Objects.requireNonNull(label, "label");
        return new InspectField<>() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public String format(String value) {
                return value == null ? "" : value;
            }

            @Override
            public Result<String> parse(String text) {
                return Result.ok(text == null ? "" : text);
            }
        };
    }

    /** A whole number between the two bounds, both inclusive. */
    static InspectField<Integer> integer(String label, int min, int max) {
        Objects.requireNonNull(label, "label");
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " above max " + max);
        }
        return new InspectField<>() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public String format(Integer value) {
                return value == null ? "" : String.valueOf(value);
            }

            @Override
            public Result<Integer> parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                if (trimmed.isEmpty()) {
                    return Result.bad(label + " needs a number");
                }
                int value;
                try {
                    value = Integer.parseInt(trimmed);
                }
                catch (NumberFormatException notANumber) {
                    return Result.bad("\"" + trimmed + "\" is not a whole number");
                }
                if (value < min || value > max) {
                    return Result.bad(label + " is between " + min + " and " + max);
                }
                return Result.ok(value);
            }
        };
    }

    /**
     * A number that may have a fraction, between the two bounds, both inclusive.
     *
     * <p>Its own field rather than the integer one widened, because the two refuse different text: a
     * scale of {@code 1.5} is a value to this field and a refusal to the whole-number one, and a field
     * that silently rounds would be writing a number the player did not type.
     */
    static InspectField<Double> decimal(String label, double min, double max) {
        Objects.requireNonNull(label, "label");
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " above max " + max);
        }
        return new InspectField<>() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public String format(Double value) {
                if (value == null) {
                    return "";
                }
                return value == Math.floor(value) ? String.valueOf(value.longValue())
                        : String.valueOf(value);
            }

            @Override
            public Result<Double> parse(String text) {
                String trimmed = text == null ? "" : text.trim();
                if (trimmed.isEmpty()) {
                    return Result.bad(label + " needs a number");
                }
                double value;
                try {
                    value = Double.parseDouble(trimmed);
                }
                catch (NumberFormatException notANumber) {
                    return Result.bad("\"" + trimmed + "\" is not a number");
                }
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    return Result.bad("\"" + trimmed + "\" is not a finite number");
                }
                if (value < min || value > max) {
                    return Result.bad(label + " is between " + min + " and " + max);
                }
                return Result.ok(value);
            }
        };
    }

    /** A flag: the usual spellings of yes and no, and nothing else, because a typo is not a third state. */
    static InspectField<Boolean> flag(String label) {
        Objects.requireNonNull(label, "label");
        return new InspectField<>() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public String format(Boolean value) {
                if (value == null) {
                    return "";
                }
                return value ? "true" : "false";
            }

            @Override
            public Result<Boolean> parse(String text) {
                String trimmed = text == null ? "" : text.trim().toLowerCase();
                if (trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("on")
                        || trimmed.equals("1")) {
                    return Result.ok(Boolean.TRUE);
                }
                if (trimmed.equals("false") || trimmed.equals("no") || trimmed.equals("off")
                        || trimmed.equals("0")) {
                    return Result.ok(Boolean.FALSE);
                }
                return Result.bad("\"" + trimmed + "\" is not a flag -- true or false");
            }
        };
    }
}
