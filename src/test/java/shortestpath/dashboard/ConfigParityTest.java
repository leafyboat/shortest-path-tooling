package shortestpath.dashboard;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import shortestpath.ShortestPathConfig;
import shortestpath.TestShortestPathConfig;

/**
 * Config-surface parity lint between {@link ShortestPathConfig} and the dashboard
 * twin {@link DashboardPathfinderConfig}.
 *
 * <p>Four properties are asserted for every runtime-retained {@code @ConfigItem}
 * on the interface:
 * <ol>
 *   <li><b>Presence</b> — {@code DashboardPathfinderConfig} declares a method with
 *       the same name (a fall-through to the interface default is a failure —
 *       the twin exists so scenario config semantics never silently depend on
 *       upstream defaults).</li>
 *   <li><b>Setter</b> — the twin declares a {@code setXxx} setter following its
 *       {@code set} + capitalized-name convention so presets can mutate it.</li>
 *   <li><b>Dispatch</b> — {@code DashboardScenarioRunner.applyConfigOverrides}
 *       accepts the item's {@code keyName}, so {@code config_overrides} rows can
 *       stub it.</li>
 *   <li><b>Default parity</b> — the value returned by a fresh twin equals the
 *       value returned by a fresh {@link TestShortestPathConfig}, unless the
 *       method is named in {@link #ALLOWLIST} with a justification.</li>
 * </ol>
 *
 * <p>Deliberate divergences live in the two allowlist maps below — never silence
 * a fixable miss by allowlisting it. {@link TestShortestPathConfig} is never
 * lint-bound: it participates only as the default-parity reference and its
 * members/setters are never enumerated or asserted.
 *
 * <p>The test is deterministic and read-only: it enumerates reflection metadata
 * and invokes dispatch on fresh twin instances only — no shared state, safe
 * under Gradle parallel test execution.
 */
public class ConfigParityTest {

    /**
     * Methods deliberately NOT twinned: exempt from presence, setter, and
     * dispatch checks (they still run through default parity — a method absent
     * from the twin invokes the same interface default on both sides, so the
     * comparison stays meaningful). Map value = one-line justification.
     */
    private static final Map<String, String> NO_TWIN_ALLOWLIST = new TreeMap<>();

    static {
        // Display-only items — colours are never read during pathfinding.
        NO_TWIN_ALLOWLIST.put("colourBankPickupHighlight", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourCollisionMap", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourPath", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourPathCalculating", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourPathUnreachable", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourTeleportPulse", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourText", "display colour; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("colourTransports", "display colour; never read during pathfinding");
        // Display-only items — overlay/render toggles.
        NO_TWIN_ALLOWLIST.put("drawClickPoints", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawCollisionMap", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawDebugPanel", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawMap", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawMinimap", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawTiles", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("drawTransports", "overlay toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("highlightBankPickupItems", "overlay highlight; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("highlightInventoryItems", "overlay highlight; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("highlightSpellbookSpells", "overlay highlight; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("pathStyle", "render style; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("showBankPickupInfo", "info display; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("showTeleportPulse", "render toggle; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("showTileCounter", "info display; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("showTransportInfo", "info display; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("showUnreachableText", "info display; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("tileCounterStep", "display counter stride; never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("unreachableText", "unreachable-banner text; display only");
        NO_TWIN_ALLOWLIST.put("clearPathHotkey", "keybind; never read during pathfinding");
        // Plugin-side runtime knobs — read by ShortestPathPlugin's restart /
        // completion / broadcast logic, never by PathfinderConfig; the harness
        // runs a single pathfinding pass so they cannot affect a route.
        NO_TWIN_ALLOWLIST.put("cancelInstead", "restart-loop knob; plugin-side, never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("recalculateDistance", "restart-loop knob; plugin-side, never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("reachedDistance", "path-completion distance; plugin-side, never read during pathfinding");
        NO_TWIN_ALLOWLIST.put("postTransports", "plugin-message broadcast toggle; never read during pathfinding");
        // Derived sets — their interface defaults delegate to the twinned
        // booleans (useTeleportationPortalsPoh / usePohMountedItems), so the
        // full-on/full-off surface is already stub-able; partial sets are
        // inexpressible in the config_overrides grammar.
        NO_TWIN_ALLOWLIST.put("pohNexusPortals", "interface default derives the set from twinned useTeleportationPortalsPoh; partial sets inexpressible in config_overrides");
        NO_TWIN_ALLOWLIST.put("pohMountedItems", "interface default derives the set from twinned usePohMountedItems; partial sets inexpressible in config_overrides");
    }

    /**
     * Twinned methods whose default intentionally differs from
     * {@link TestShortestPathConfig}. Map value = one-line justification; every
     * entry must also be justified on the twin's class doc.
     */
    private static final Map<String, String> ALLOWLIST = new TreeMap<>();

    static {
        ALLOWLIST.put("calculationCutoff",
            "500 vs 5 — dashboard runs need a larger cutoff (documented twin class-doc exception)");
        ALLOWLIST.put("currencyThreshold",
            "10_000_000 vs 100_000 — generous so currency gates never block committed scenarios (documented twin class-doc exception)");
        ALLOWLIST.put("useTeleportationItems",
            "NONE vs INVENTORY_NON_CONSUMABLE — presets always stub it per-row (documented twin class-doc exception)");
    }

    /**
     * The config-item surface: zero-arg, non-void methods carrying
     * {@code @ConfigItem}. The interface also annotates the paired
     * {@code setBuilt*(String)} write methods with the same {@code keyName}
     * (so the values persist through RuneLite's config store) — those carry a
     * parameter and return {@code void} and are the write half of items the
     * getters already cover, so they are filtered out here.
     */
    private static List<Method> configItems() {
        List<Method> items = new ArrayList<>();
        for (Method m : ShortestPathConfig.class.getMethods()) {
            if (m.isAnnotationPresent(ConfigItem.class)
                && m.getParameterCount() == 0
                && m.getReturnType() != void.class) {
                items.add(m);
            }
        }
        items.sort(Comparator.comparing(Method::getName));
        return items;
    }

    private static String keyName(Method m) {
        return m.getAnnotation(ConfigItem.class).keyName();
    }

    private static String setterName(String methodName) {
        return "set" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
    }

    private static String stubValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "true";
        }
        if (type == int.class || type == Integer.class) {
            return "1";
        }
        if (type == String.class) {
            return "x";
        }
        if (type.isEnum()) {
            return ((Enum<?>) type.getEnumConstants()[0]).name();
        }
        return null;
    }

    @Test
    public void everyConfigItemHasATwinOverride() {
        List<String> missing = new ArrayList<>();
        for (Method m : configItems()) {
            if (NO_TWIN_ALLOWLIST.containsKey(m.getName())) {
                continue;
            }
            try {
                DashboardPathfinderConfig.class.getDeclaredMethod(m.getName());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName() + " (keyName=" + keyName(m) + ")");
            }
        }
        // Forward-compat report: twin members with no @ConfigItem counterpart
        // are non-fatal — printed for visibility only.
        List<String> orphan = new ArrayList<>();
        for (Method m : DashboardPathfinderConfig.class.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class
                && !m.getName().startsWith("set") && !m.getName().startsWith("is")) {
                try {
                    if (ShortestPathConfig.class.getMethod(m.getName())
                        .isAnnotationPresent(ConfigItem.class)) {
                        continue;
                    }
                } catch (NoSuchMethodException e) {
                    // fall through — not on the interface at all
                }
                orphan.add(m.getName());
            }
        }
        if (!orphan.isEmpty()) {
            Collections.sort(orphan);
            System.out.println("ConfigParityTest forward-compat twin members"
                + " (no @ConfigItem counterpart, non-fatal): " + orphan);
        }
        assertTrue("@ConfigItem methods missing a DashboardPathfinderConfig override: "
            + missing, missing.isEmpty());
    }

    @Test
    public void everyConfigItemHasATwinSetter() {
        List<String> missing = new ArrayList<>();
        for (Method m : configItems()) {
            if (NO_TWIN_ALLOWLIST.containsKey(m.getName())) {
                continue;
            }
            String setter = setterName(m.getName());
            try {
                DashboardPathfinderConfig.class.getDeclaredMethod(setter, m.getReturnType());
            } catch (NoSuchMethodException e) {
                missing.add(setter + "(" + m.getReturnType().getSimpleName() + ")");
            }
        }
        assertTrue("@ConfigItem methods missing a twin setXxx setter: " + missing,
            missing.isEmpty());
    }

    @Test
    public void everyConfigItemIsDispatchable() throws Exception {
        Method apply = DashboardScenarioRunner.class.getDeclaredMethod(
            "applyConfigOverrides", Map.class, DashboardPathfinderConfig.class);
        apply.setAccessible(true);
        List<String> missing = new ArrayList<>();
        for (Method m : configItems()) {
            if (NO_TWIN_ALLOWLIST.containsKey(m.getName())) {
                continue;
            }
            String stub = stubValue(m.getReturnType());
            if (stub == null) {
                missing.add(keyName(m) + " (no stub rule for "
                    + m.getReturnType().getSimpleName() + ")");
                continue;
            }
            Map<String, String> overrides = Collections.singletonMap(keyName(m), stub);
            try {
                apply.invoke(null, overrides, new DashboardPathfinderConfig());
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalArgumentException
                    || cause instanceof IllegalStateException) {
                    missing.add(keyName(m) + " (" + cause.getMessage() + ")");
                } else {
                    throw e;
                }
            }
        }
        assertTrue("@ConfigItem keyNames rejected by applyConfigOverrides: " + missing,
            missing.isEmpty());
    }

    @Test
    public void twinDefaultsMatchTestConfig() throws Exception {
        DashboardPathfinderConfig twin = new DashboardPathfinderConfig();
        TestShortestPathConfig reference = new TestShortestPathConfig();
        List<String> divergent = new ArrayList<>();
        for (Method m : configItems()) {
            if (ALLOWLIST.containsKey(m.getName())) {
                continue;
            }
            Object twinValue = m.invoke(twin);
            Object referenceValue = m.invoke(reference);
            if (!Objects.equals(twinValue, referenceValue)) {
                divergent.add(m.getName() + " (twin=" + twinValue
                    + ", reference=" + referenceValue + ")");
            }
        }
        assertTrue("Config defaults divergent between the dashboard twin and"
            + " TestShortestPathConfig (declare deliberate differences in ALLOWLIST): "
            + divergent, divergent.isEmpty());
    }
}
