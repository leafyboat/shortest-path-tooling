package shortestpath.benchmark.canonical;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;
import shortestpath.ItemVariations;
import shortestpath.transport.PohNexusPortal;
import shortestpath.transport.PohMountedItem;

public class CanonicalAccountCompilerTest {
    private static Path corpus() {
        return TestCorpus.dir();
    }

    @Test
    public void accountCompilationUsesConcreteFactsAndQuestStates() throws Exception {
        CanonicalAccountProfile profile = CanonicalAccountProfileLoader.load(
            corpus().resolve("accounts/account-profiles-v1.json")).get("mid");
        CanonicalAccountCompiler.CompiledAccount compiled = new CanonicalAccountCompiler()
            .compile("mid", profile, true);
        assertNotNull(compiled.getConfig().bank);
        assertEquals(80, compiled.getClient().getBoostedSkillLevel(Skill.ATTACK));
        assertEquals(Integer.valueOf(profile.getVarbits().get(10449)),
            Integer.valueOf(compiled.getClient().getVarbitValue(10449)));
        assertEquals(net.runelite.api.QuestState.FINISHED,
            compiled.getConfig().getQuestState(Quest.THE_GRAND_TREE));
        assertEquals(net.runelite.api.QuestState.NOT_STARTED,
            compiled.getConfig().getQuestState(Quest.RUM_DEAL));
        assertEquals(EnumSet.of(
                PohNexusPortal.ARDOUGNE,
                PohNexusPortal.BARROWS,
                PohNexusPortal.CAMELOT,
                PohNexusPortal.FALADOR,
                PohNexusPortal.KOUREND,
                PohNexusPortal.VARROCK),
            CanonicalAccountCompiler.pohNexusPortals(profile));
        assertEquals(profile.getBenchmarkNowMinutes(),
            ((CanonicalTestPathfinderConfig) compiled.getConfig()).evaluationTimeMinutes());
    }

    @Test
    public void accountCompilerPropagatesPlantedSpiritTreesWithoutPohCoupling() throws Exception {
        Map<String, CanonicalAccountProfile> profiles = CanonicalAccountProfileLoader.load(
            corpus().resolve("accounts/account-profiles-v1.json"));
        CanonicalAccountCompiler compiler = new CanonicalAccountCompiler();

        CanonicalAccountCompiler.CompiledAccount early = compiler.compile("early", profiles.get("early"), true);
        CanonicalAccountCompiler.CompiledAccount mid = compiler.compile("mid", profiles.get("mid"), true);
        CanonicalAccountCompiler.CompiledAccount end = compiler.compile("end", profiles.get("end"), true);
        CanonicalAccountCompiler.CompiledAccount maxed = compiler.compile("maxed", profiles.get("maxed"), true);

        assertEquals(Set.of(), early.getConfig().availableSpiritTrees);
        assertEquals(Set.of("Farming Guild"), mid.getConfig().availableSpiritTrees);
        assertEquals(Set.of("Farming Guild", "Port Sarim"), end.getConfig().availableSpiritTrees);
        assertEquals(Set.of("Farming Guild", "Port Sarim", "Etceteria", "Brimhaven", "Hosidius"),
            maxed.getConfig().availableSpiritTrees);
        assertFalse(CanonicalAccountCompiler.canonicalConfig(profiles.get("mid"), true).usePohSpiritTree());
        assertTrue(CanonicalAccountCompiler.canonicalConfig(profiles.get("end"), true).usePohSpiritTree());
    }

    @Test
    public void unknownPlantedSpiritTreeFailsCompilation() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(
            corpus().resolve("accounts/account-profiles-v1.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject profileJson = fixture.getAsJsonObject("profiles").getAsJsonObject("early").deepCopy();
        profileJson.getAsJsonArray("plantedSpiritTrees").add("UNKNOWN_TREE");
        CanonicalAccountProfile profile = CanonicalAccountProfile.fromJson(
            profileJson, fixture.get("benchmarkNowMinutes").getAsLong());

        try {
            new CanonicalAccountCompiler().compile("early", profile, true);
            fail("unknown planted spirit tree should fail compilation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("UNKNOWN_TREE"));
        }
    }

    @Test
    public void accountCompilerPreservesMixedMountedItems() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(
            corpus().resolve("accounts/account-profiles-v1.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject profileJson = fixture.getAsJsonObject("profiles").getAsJsonObject("early").deepCopy();
        JsonObject poh = profileJson.getAsJsonObject("poh");
        poh.addProperty("mountedGlory", true);
        poh.addProperty("mountedXerics", false);
        poh.addProperty("mountedDigsite", true);
        poh.addProperty("mountedMythical", false);
        CanonicalAccountProfile profile = CanonicalAccountProfile.fromJson(
            profileJson, fixture.get("benchmarkNowMinutes").getAsLong());

        assertEquals(EnumSet.of(PohMountedItem.GLORY, PohMountedItem.DIGSITE_PENDANT),
            CanonicalAccountCompiler.pohMountedItems(profile));
        assertNotNull(new CanonicalAccountCompiler().compile("mixed", profile, true));
    }

    @Test
    public void earlyFishingTrawlerTeleportUsesCanonicalClock() throws Exception {
        CanonicalAccountProfile profile = CanonicalAccountProfileLoader.load(
            corpus().resolve("accounts/account-profiles-v1.json")).get("early");
        CanonicalAccountCompiler compiler = new CanonicalAccountCompiler();
        CanonicalAccountCompiler.CompiledAccount ready = compiler.compile("early", profile, true);
        CanonicalAccountCompiler.CompiledAccount cooldown = compiler.compileAtTime(
            "early", profile, true, 99999990L);

        assertTrue(Arrays.stream(ready.getConfig().getUsableTeleports(false))
            .anyMatch(transport -> "Fishing Trawler Minigame Teleport".equals(transport.getDisplayInfo())));
        assertTrue(Arrays.stream(cooldown.getConfig().getUsableTeleports(false))
            .noneMatch(transport -> "Fishing Trawler Minigame Teleport".equals(transport.getDisplayInfo())));
    }

    @Test
    public void itemAliasesUseProductionVariationsAndMergeIds() {
        Map<String, Integer> source = new LinkedHashMap<>();
        int airRune = ItemVariations.AIR_RUNE.getIds()[0];
        source.put("AIR_RUNE", 2);
        source.put(String.valueOf(airRune), 3);
        assertEquals(Integer.valueOf(5),
            CanonicalAccountCompiler.translateItems("inventory", source).get(airRune));
        try {
            CanonicalAccountCompiler.translateItems("bank", Map.of("NO_SUCH_ITEM", 1));
            fail("unknown symbolic item should fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bank"));
            assertTrue(expected.getMessage().contains("NO_SUCH_ITEM"));
        }
    }
}
