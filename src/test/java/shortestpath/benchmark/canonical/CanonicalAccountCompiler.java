package shortestpath.benchmark.canonical;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import shortestpath.ItemVariations;
import shortestpath.JewelleryBoxTier;
import shortestpath.TeleportationItem;
import shortestpath.dashboard.DashboardPathfinderConfig;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.transport.PohNexusPortal;
import shortestpath.transport.PohMountedItem;

/** Compiles one language-neutral profile into the production legacy config shape. */
public final class CanonicalAccountCompiler {
    public CompiledAccount compile(String profileName, CanonicalAccountProfile profile, boolean allowTransports)
    {
        return compileAtTime(profileName, profile, allowTransports, profile.getBenchmarkNowMinutes());
    }

    CompiledAccount compileAtTime(String profileName, CanonicalAccountProfile profile,
        boolean allowTransports, long benchmarkNowMinutes)
    {
        Client client = mock(Client.class);
        stubClient(client, profileName, profile);
        DashboardPathfinderConfig config = canonicalConfig(profile, allowTransports);
        CanonicalTestPathfinderConfig pathfinderConfig = new CanonicalTestPathfinderConfig(
            client, config, profile.getCompletedQuests(), benchmarkNowMinutes);
        pathfinderConfig.bank = itemContainer(translateItems("profile " + profileName + " bank", profile.getBank()));
        pathfinderConfig.availableSpiritTrees = plantedSpiritTreeNames(profile);
        pathfinderConfig.refresh();
        return new CompiledAccount(pathfinderConfig, client);
    }

    private static void stubClient(Client client, String profileName, CanonicalAccountProfile profile) {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getClientThread()).thenReturn(Thread.currentThread());
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getVarbitValue(anyInt())).thenReturn(0);
        when(client.getVarpValue(anyInt())).thenReturn(0);
        when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(1);

        for (var entry : profile.getVarbits().entrySet()) {
            when(client.getVarbitValue(entry.getKey())).thenReturn(entry.getValue());
        }
        for (var entry : profile.getVarplayers().entrySet()) {
            when(client.getVarpValue(entry.getKey())).thenReturn(entry.getValue());
        }
        if (!profile.getVarbits().containsKey(VarbitID.FAIRY2_QUEENCURE_QUEST)) {
            when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST))
                .thenReturn(profile.isFairyRingsUnlocked() ? 100 : 0);
        }

        int total = 0;
        for (var entry : profile.getLevels().entrySet()) {
            if (entry.getKey().equals("Total") || entry.getKey().equals("Quest")) {
                continue;
            }
            when(client.getBoostedSkillLevel(skill(entry.getKey()))).thenReturn(entry.getValue());
            total = Math.addExact(total, entry.getValue());
        }
        when(client.getTotalLevel()).thenReturn(profile.getLevels().getOrDefault("Total", total));
        when(client.getVarpValue(VarPlayerID.QP)).thenReturn(profile.getLevels().getOrDefault("Quest", 0));

        Map<Integer, Integer> inventory = translateItems("profile " + profileName + " inventory", profile.getInventory());
        mergeItems(inventory, "profile " + profileName + " runePouch", profile.getRunePouch());
        doReturn(itemContainer(inventory)).when(client).getItemContainer(InventoryID.INV);
        doReturn(itemContainer("profile " + profileName + " equipment", profile.getEquipment())).when(client)
            .getItemContainer(InventoryID.WORN);
    }

    static DashboardPathfinderConfig canonicalConfig(
            CanonicalAccountProfile profile, boolean allowTransports) {
        DashboardPathfinderConfig config = new DashboardPathfinderConfig();
        config.setAvoidWilderness(false);
        config.setUseAgilityShortcuts(allowTransports);
        config.setUseGrappleShortcuts(allowTransports);
        config.setUseBoats(allowTransports);
        config.setUseCanoes(allowTransports);
        config.setUseCharterShips(allowTransports);
        config.setUseShips(allowTransports);
        config.setUseFairyRings(allowTransports);
        config.setUseGnomeGliders(allowTransports);
        config.setUseHotAirBalloons(allowTransports);
        config.setUseMagicCarpets(allowTransports);
        config.setUseMagicMushtrees(allowTransports);
        config.setUseMinecarts(allowTransports);
        config.setUseQuetzals(allowTransports);
        config.setUseSpiritTrees(allowTransports);
        config.setUseTeleportationItems(allowTransports
            ? TeleportationItem.INVENTORY_AND_BANK : TeleportationItem.NONE);
        config.setUseTeleportationLevers(allowTransports);
        config.setUseTeleportationPortals(allowTransports);
        config.setUseTeleportationSpells(allowTransports);
        config.setUseTeleportationMinigames(allowTransports);
        config.setUseWildernessObelisks(allowTransports);
        config.setUseSeasonalTransports(false);
        config.setIncludeBankPath(true);
        config.setBypassVarbitChecks(false);
        config.setCurrencyThreshold(Integer.MAX_VALUE);
        config.setCalculationCutoff(500);

        config.setUsePoh(true);
        config.setUsePohFairyRing(allowTransports && profile.getPoh().isFairyRing());
        config.setUsePohSpiritTree(allowTransports && profile.getPoh().isSpiritTree());
        config.setUsePohObelisk(allowTransports && profile.getPoh().isObelisk());
        config.setPohJewelleryBoxTier(jewelleryBox(profile.getPoh().getJewelleryBox()));
        config.setPohMountedItems(pohMountedItems(profile));
        Set<PohNexusPortal> pohNexusPortals = pohNexusPortals(profile);
        config.setUseTeleportationPortalsPoh(allowTransports && !pohNexusPortals.isEmpty());
        config.setPohNexusPortals(pohNexusPortals);

        // All Java user preference penalties are neutral; intrinsic transport costs stay in plugin data.
        config.setCostAgilityShortcuts(0);
        config.setCostGrappleShortcuts(0);
        config.setCostBoats(0);
        config.setCostCanoes(0);
        config.setCostCharterShips(0);
        config.setCostShips(0);
        config.setCostFairyRings(0);
        config.setCostGnomeGliders(0);
        config.setCostHotAirBalloons(0);
        config.setCostMagicCarpets(0);
        config.setCostMagicMushtrees(0);
        config.setCostMinecarts(0);
        config.setCostQuetzals(0);
        config.setCostQuetzalWhistle(0);
        config.setCostSpiritTrees(0);
        config.setCostNonConsumableTeleportationItems(0);
        config.setCostConsumableTeleportationItems(0);
        config.setCostTeleportationBoxes(0);
        config.setCostTeleportationLevers(0);
        config.setCostTeleportationPortals(0);
        config.setCostTeleportationSpells(0);
        config.setCostTeleportationMinigames(0);
        config.setCostWildernessObelisks(0);
        config.setCostSeasonalTransports(0);
        return config;
    }

    static Set<PohNexusPortal> pohNexusPortals(CanonicalAccountProfile profile) {
        String mode = profile.getPoh().getPortalMode();
        if (mode.equals("all")) {
            return EnumSet.allOf(PohNexusPortal.class);
        }
        if (!mode.equals("selected")) {
            throw new IllegalArgumentException("unknown POH portal mode: " + mode);
        }

        Set<PohNexusPortal> result = EnumSet.noneOf(PohNexusPortal.class);
        for (String displayInfo : profile.getPoh().getPortalDestinations()) {
            PohNexusPortal portal = PohNexusPortal.fromDisplayInfo(displayInfo);
            if (portal == null) {
                throw new IllegalArgumentException("unknown POH portal: " + displayInfo);
            }
            result.add(portal);
        }
        return result;
    }

    static Set<PohMountedItem> pohMountedItems(CanonicalAccountProfile profile) {
        Set<PohMountedItem> result = EnumSet.noneOf(PohMountedItem.class);
        if (profile.getPoh().isMountedGlory()) {
            result.add(PohMountedItem.GLORY);
        }
        if (profile.getPoh().isMountedXerics()) {
            result.add(PohMountedItem.XERICS_TALISMAN);
        }
        if (profile.getPoh().isMountedDigsite()) {
            result.add(PohMountedItem.DIGSITE_PENDANT);
        }
        if (profile.getPoh().isMountedMythical()) {
            result.add(PohMountedItem.MYTHICAL_CAPE);
        }
        return result;
    }

    static Set<String> plantedSpiritTreeNames(CanonicalAccountProfile profile) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : profile.getPlantedSpiritTrees()) {
            switch (name) {
                case "FARMING_GUILD": result.add("Farming Guild"); break;
                case "PORT_SARIM": result.add("Port Sarim"); break;
                case "ETCETERIA": result.add("Etceteria"); break;
                case "BRIMHAVEN": result.add("Brimhaven"); break;
                case "HOSIDIUS": result.add("Hosidius"); break;
                default: throw new IllegalArgumentException("unknown planted spirit tree: " + name);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static JewelleryBoxTier jewelleryBox(String name) {
        switch (name) {
            case "NoJewelleryBox": return JewelleryBoxTier.NONE;
            case "FancyJewelleryBox": return JewelleryBoxTier.FANCY;
            case "OrnateJewelleryBox": return JewelleryBoxTier.ORNATE;
            default: throw new IllegalArgumentException("unknown canonical POH jewellery box: " + name);
        }
    }

    private static ItemContainer itemContainer(String field, Map<String, Integer> values) {
        return itemContainer(translateItems(field, values));
    }

    private static ItemContainer itemContainer(Map<Integer, Integer> values) {
        ItemContainer container = mock(ItemContainer.class);
        List<Item> items = new ArrayList<>();
        for (var entry : values.entrySet()) {
            items.add(new Item(entry.getKey(), entry.getValue()));
        }
        when(container.getItems()).thenReturn(items.toArray(new Item[0]));
        return container;
    }

    static Map<Integer, Integer> translateItems(String field, Map<String, Integer> values) {
        Map<Integer, Integer> result = new HashMap<>();
        for (var entry : values.entrySet()) {
            int id;
            try {
                id = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException ignored) {
                ItemVariations variation = ItemVariations.fromName(entry.getKey());
                if (variation == null) {
                    throw new IllegalArgumentException("unknown symbolic item: field=" + field
                        + ", key=" + entry.getKey());
                }
                id = variation.getIds()[0];
            }
            add(result, id, entry.getValue(), field, entry.getKey());
        }
        return result;
    }

    private static void mergeItems(Map<Integer, Integer> target, String field, Map<String, Integer> values) {
        for (var entry : translateItems(field, values).entrySet()) {
            add(target, entry.getKey(), entry.getValue(), field, String.valueOf(entry.getKey()));
        }
    }

    private static void add(Map<Integer, Integer> values, int id, int quantity, String field, String key) {
        try {
            values.put(id, Math.addExact(values.getOrDefault(id, 0), quantity));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("item quantity overflow: field=" + field + ", key=" + key, e);
        }
    }

    private static Skill skill(String name) {
        try {
            return Skill.valueOf(name.toUpperCase(Locale.ROOT).replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown canonical skill name: " + name, e);
        }
    }

    public static final class CompiledAccount {
        private final TestPathfinderConfig config;
        private final Client client;

        private CompiledAccount(TestPathfinderConfig config, Client client) {
            this.config = config;
            this.client = client;
        }

        public TestPathfinderConfig getConfig() { return config; }
        public Client getClient() { return client; }
    }
}
