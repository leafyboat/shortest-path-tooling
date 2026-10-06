package shortestpath.benchmark.canonical;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable Java view of one exported canonical AccountState. */
public final class CanonicalAccountProfile {
    private final long benchmarkNowMinutes;
    private final Map<String, Integer> levels;
    private final Set<String> completedQuests;
    private final Map<Integer, Integer> varbits;
    private final Map<Integer, Integer> varplayers;
    private final Map<String, Integer> inventory;
    private final Map<String, Integer> equipment;
    private final Map<String, Integer> runePouch;
    private final Map<String, Integer> bank;
    private final Poh poh;
    private final Set<String> plantedSpiritTrees;
    private final boolean fairyRingsUnlocked;

    private CanonicalAccountProfile(JsonObject json, long benchmarkNowMinutes) {
        this.benchmarkNowMinutes = benchmarkNowMinutes;
        levels = integers(json, "levels");
        completedQuests = stringsSet(json, "completedQuests");
        varbits = integerKeys(json, "varbits");
        varplayers = integerKeys(json, "varplayers");
        inventory = integers(json, "inventory");
        equipment = integers(json, "equipment");
        runePouch = integers(json, "runePouch");
        bank = integers(json, "bank");
        poh = Poh.fromJson(required(json, "poh").getAsJsonObject());
        plantedSpiritTrees = stringsSet(json, "plantedSpiritTrees");
        fairyRingsUnlocked = required(json, "fairyRingsUnlocked").getAsBoolean();
    }

    public static CanonicalAccountProfile fromJson(JsonObject json, long benchmarkNowMinutes) {
        return new CanonicalAccountProfile(json, benchmarkNowMinutes);
    }

    public long getBenchmarkNowMinutes() { return benchmarkNowMinutes; }
    public Map<String, Integer> getLevels() { return levels; }
    public Set<String> getCompletedQuests() { return completedQuests; }
    public Map<Integer, Integer> getVarbits() { return varbits; }
    public Map<Integer, Integer> getVarplayers() { return varplayers; }
    public Map<String, Integer> getInventory() { return inventory; }
    public Map<String, Integer> getEquipment() { return equipment; }
    public Map<String, Integer> getRunePouch() { return runePouch; }
    public Map<String, Integer> getBank() { return bank; }
    public Poh getPoh() { return poh; }
    public Set<String> getPlantedSpiritTrees() { return plantedSpiritTrees; }
    public boolean isFairyRingsUnlocked() { return fairyRingsUnlocked; }

    private static Map<String, Integer> integers(JsonObject json, String field) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : required(json, field).getAsJsonObject().entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<Integer, Integer> integerKeys(JsonObject json, String field) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (var entry : required(json, field).getAsJsonObject().entrySet()) {
            try {
                result.put(Integer.valueOf(entry.getKey()), entry.getValue().getAsInt());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("profile field " + field
                    + " has non-integer key " + entry.getKey(), e);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> stringsSet(JsonObject json, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (var value : required(json, field).getAsJsonArray()) {
            result.add(value.getAsString());
        }
        return Collections.unmodifiableSet(result);
    }

    private static com.google.gson.JsonElement required(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalArgumentException("account profile missing field " + field);
        }
        return json.get(field);
    }

    public static final class Poh {
        private final String jewelleryBox;
        private final String portalMode;
        private final List<String> portalDestinations;
        private final boolean fairyRing;
        private final boolean spiritTree;
        private final boolean obelisk;
        private final boolean mountedGlory;
        private final boolean mountedXerics;
        private final boolean mountedDigsite;
        private final boolean mountedMythical;

        private Poh(JsonObject json) {
            jewelleryBox = string(json, "jewelleryBox");
            JsonObject portals = json.get("portals").getAsJsonObject();
            portalMode = string(portals, "mode");
            List<String> destinations = new ArrayList<>();
            for (var value : portals.get("destinations").getAsJsonArray()) {
                destinations.add(value.getAsString());
            }
            portalDestinations = Collections.unmodifiableList(destinations);
            fairyRing = json.get("fairyRing").getAsBoolean();
            spiritTree = json.get("spiritTree").getAsBoolean();
            obelisk = json.get("obelisk").getAsBoolean();
            mountedGlory = json.get("mountedGlory").getAsBoolean();
            mountedXerics = json.get("mountedXerics").getAsBoolean();
            mountedDigsite = json.get("mountedDigsite").getAsBoolean();
            mountedMythical = json.get("mountedMythical").getAsBoolean();
        }

        static Poh fromJson(JsonObject json) { return new Poh(json); }
        public String getJewelleryBox() { return jewelleryBox; }
        public String getPortalMode() { return portalMode; }
        public List<String> getPortalDestinations() { return portalDestinations; }
        public boolean isFairyRing() { return fairyRing; }
        public boolean isSpiritTree() { return spiritTree; }
        public boolean isObelisk() { return obelisk; }
        public boolean isMountedGlory() { return mountedGlory; }
        public boolean isMountedXerics() { return mountedXerics; }
        public boolean isMountedDigsite() { return mountedDigsite; }
        public boolean isMountedMythical() { return mountedMythical; }

        private static String string(JsonObject json, String field) {
            if (!json.has(field)) {
                throw new IllegalArgumentException("POH field missing " + field);
            }
            return json.get(field).getAsString();
        }
    }
}
