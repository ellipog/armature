package dev.ellipog.armature.integration.emi;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.integration.Viewers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Whether the installed EMI can still be the integration when JEI is beside it.
 *
 * <h2>The observed break this answers</h2>
 *
 * <p>EMI 1.1.24's JEI bridge — EMI drawing JEI's recipes — was compiled against a JEI where the text
 * widget's {@code setPosition(int, int)} existed only through a generic supertype. When JEI later
 * declared its own abstract {@code setPosition(int, int)} returning the widget type, EMI's widget
 * kept the inherited method with the same name and parameters and a different <i>descriptor</i>: it
 * returns the erased {@code IPlaceable}, where the interface promises {@code ITextWidget}. Every
 * JEI-derived page opened inside EMI's recipe screen therefore throws {@code AbstractMethodError}
 * while it is built, and the screen is left unresponsive. That is upstream and cannot be fixed from
 * here; what <i>can</i> be chosen is whose screen draws the quests, so this pair demotes EMI and the
 * integration lands on JEI.
 *
 * <h2>The two wrong questions this probe asked before it asked the right one</h2>
 *
 * <p><b>First it asked whether the method existed.</b> It did — inherited from the generic
 * superclass — so the probe reported EMI healthy and the demotion never fired on the very client it
 * was written for. <b>Then it asked what {@code getMethod} returned</b> and compared return types.
 * But {@code getMethod} on a class that does not implement an abstract interface method returns the
 * <i>interface's abstract method itself</i>, which of course agrees with itself; the probe still said
 * healthy. What the JVM actually does on {@code invokeinterface} is look for a <b>concrete</b>
 * method with the <b>exact descriptor</b> — name, parameters and return type. So that is what this
 * asks, and the corrected rule was checked offline against the real EMI and JEI jars before it was
 * trusted: it reports the installed pair broken, while a bridge method (what a class compiled
 * against the newer interface carries) satisfies it.
 *
 * <h2>Why a probe and not a version range</h2>
 *
 * <p>A version rule would need a boundary for every future release of both mods and would keep
 * avoiding EMI long after it was fixed. The probe asks the capability, so a later EMI that provides
 * the method is trusted again with no edit here.
 *
 * <h2>Trust by default</h2>
 *
 * <p>Only a positive finding demotes. If the class cannot be found, or reflection refuses, the
 * answer is "EMI is fine": a compatibility rule that failed closed would take EMI away from clients
 * where it works, which is a worse failure than the one it guards.
 */
public final class EmiCompatibility {

    /**
     * The class named in the crash: EMI's JEI text widget.
     *
     * <p>Named as a string rather than a type, deliberately, and part of why this is a separate
     * file: loading EMI's bridge class needs JEI present, and the probe must be able to ask its
     * question without linking against either mod.
     */
    private static final String JEI_TEXT_WIDGET = "dev.emi.emi.jemi.impl.extras.JemiTextWidget";

    /** The method the bridge does not actually implement. */
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
            answered = !satisfiesSetPosition(loadWidget());
            broken = answered;
        }
        return answered;
    }

    /**
     * Can the class dispatch every abstract {@code setPosition(int, int)} its interfaces declare?
     * {@code null} (a class that could not be looked up) is trusted.
     *
     * <p>Package-private and class-taking so the rule is testable with fakes: a test JVM loads no
     * EMI, and the bytecode shape being checked — a class compiled against an older interface and
     * run against a newer one — cannot be produced from source, because javac would demand the
     * bridge method and add it.
     */
    static boolean satisfiesSetPosition(Class<?> widget) {
        if (widget == null) {
            return true;
        }
        try {
            List<Method> candidates = concreteSetPositions(widget);
            for (Class<?> iface : interfacesOf(widget)) {
                for (Method required : iface.getDeclaredMethods()) {
                    if (!isSetPosition(required)
                            || Modifier.isStatic(required.getModifiers())
                            || !Modifier.isAbstract(required.getModifiers())) {
                        // Not the method, or a default that serves itself without the class.
                        continue;
                    }
                    if (!servedBy(candidates, required)) {
                        return false;
                    }
                }
            }
        }
        catch (RuntimeException | LinkageError e) {
            Constants.LOG.debug("armature: cannot read {}'s methods ({}); trusting it",
                    widget.getName(), e.toString());
            return true;
        }
        return true;
    }

    /**
     * The JVM's dispatch rule for one method: a concrete method with the exact descriptor must
     * exist — the return type included, because that is part of what {@code invokeinterface}
     * resolves.
     *
     * <p>Compared by identity rather than assignability, deliberately: if a class overrode the
     * method covariantly, javac would also have emitted a bridge with the exact descriptor, so an
     * exact match is both what dispatch needs and what a healthy class has.
     */
    static boolean servedBy(List<Method> candidates, Method required) {
        for (Method candidate : candidates) {
            if (candidate.getReturnType() == required.getReturnType()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every concrete {@code setPosition(int, int)} the widget can dispatch to: public, non-abstract
     * methods from its class hierarchy, plus interface defaults.
     *
     * <p>Interface defaults count because they serve the call without the class implementing
     * anything — and abstract interface methods deliberately do not, which is the trap the first
     * version of this file fell into.
     */
    private static List<Method> concreteSetPositions(Class<?> widget) {
        List<Method> found = new ArrayList<>();
        for (Class<?> current = widget; current != null && current != Object.class;
                current = current.getSuperclass()) {
            collect(current.getDeclaredMethods(), found);
        }
        for (Class<?> iface : interfacesOf(widget)) {
            collect(iface.getDeclaredMethods(), found);
        }
        return found;
    }

    private static void collect(Method[] methods, List<Method> found) {
        for (Method method : methods) {
            if (isSetPosition(method)
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isAbstract(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                found.add(method);
            }
        }
    }

    private static boolean isSetPosition(Method method) {
        return SET_POSITION.equals(method.getName())
                && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == int.class
                && method.getParameterTypes()[1] == int.class;
    }

    /** Every interface of the class and of its superclasses, transitively. */
    private static Set<Class<?>> interfacesOf(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            collectInterfaces(current, found);
        }
        return found;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> found) {
        for (Class<?> iface : type.getInterfaces()) {
            if (found.add(iface)) {
                collectInterfaces(iface, found);
            }
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
