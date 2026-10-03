package dev.ellipog.armature.integration.emi;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.integration.Viewers;

import java.util.function.Predicate;

/**
 * Whether the installed EMI can still be the integration when JEI is beside it.
 *
 * <h2>The observed break this answers</h2>
 *
 * <p>EMI 1.1.24's JEMI bridge — EMI drawing JEI's recipes — does not implement
 * {@code ITextWidget.setPosition(int, int)}, which JEI 19.57 calls. Every JEI-derived page opened
 * inside EMI's recipe screen therefore throws {@code AbstractMethodError} while the page is being
 * built, and the screen is left unresponsive. That is upstream, and it cannot be fixed from here;
 * what <i>can</i> be chosen is whose screen draws the quests. So when this pair is present, the
 * priority chain demotes EMI and the integration lands on JEI — the rows are thinner, but they are
 * not nothing.
 *
 * <h2>Why a probe and not a version range</h2>
 *
 * <p>A version rule would need a boundary for every future release of both mods, and would keep
 * avoiding EMI long after it was fixed. The probe asks the actual question — does EMI's bridge class
 * implement the method JEI now has — so a later EMI that implements it is trusted again with no
 * edit here. It is the same argument as everywhere else in this seam: test the capability, not the
 * label.
 *
 * <h2>Trust by default</h2>
 *
 * <p>Only a positive finding demotes. If the class cannot be found, or reflection refuses, the
 * answer is "EMI is fine" — a compatibility rule that fails closed would take EMI away from clients
 * where it works, which is a worse failure than the one it guards.
 */
public final class EmiCompatibility {

    /**
     * The class named in the crash: EMI's JEMI text widget.
     *
     * <p>Named as a string rather than a type, deliberately, and it is the whole reason this file is
     * a separate one: loading EMI's bridge class needs JEI present, and the probe must be able to
     * answer without ever linking against either.
     */
    private static final String JEI_TEXT_WIDGET = "dev.emi.emi.jemi.impl.extras.JemiTextWidget";

    /** The method JEI's {@code ITextWidget} declares and EMI's widget was missing. */
    private static final String SET_POSITION = "setPosition";

    private static volatile Boolean broken;

    private EmiCompatibility() {
    }

    /**
     * Whether this viewer must be skipped because it cannot work beside another that is installed.
     *
     * <p>Only EMI can be demoted, and only while both it and JEI are loaded — the bridge only exists
     * for that pair. Every other id, and every other combination, answers false immediately.
     */
    public static boolean broken(Predicate<String> loaded, String modId) {
        if (!Viewers.Viewer.EMI.modId().equals(modId)) {
            return false;
        }
        if (!loaded.test(Viewers.Viewer.EMI.modId()) || !loaded.test(Viewers.Viewer.JEI.modId())) {
            return false;
        }
        Boolean answered = broken;
        if (answered == null) {
            answered = !declaresSetPosition(loadWidget());
            broken = answered;
        }
        return answered;
    }

    /**
     * Does this class have the method, declared or inherited? A class that could not be looked up
     * ({@code null}) is trusted.
     *
     * <p>Package-private and class-taking so the rule itself is testable: a test JVM does not load
     * EMI, and the fakes below are the same shape as the real pair — a class, and a method that may
     * or may not be on it.
     */
    static boolean declaresSetPosition(Class<?> widget) {
        if (widget == null) {
            return true;
        }
        try {
            widget.getMethod(SET_POSITION, int.class, int.class);
            return true;
        }
        catch (NoSuchMethodException e) {
            return false;
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("armature: cannot probe {} ({}); trusting it",
                    widget.getName(), e.toString());
            return true;
        }
    }

    /** EMI's bridge widget, or null when it cannot be found. Never initialises the class. */
    private static Class<?> loadWidget() {
        try {
            return Class.forName(JEI_TEXT_WIDGET, false, EmiCompatibility.class.getClassLoader());
        }
        catch (ClassNotFoundException | LinkageError e) {
            Constants.LOG.debug("armature: no {} to probe ({}); trusting EMI",
                    JEI_TEXT_WIDGET, e.toString());
            return null;
        }
    }
}
