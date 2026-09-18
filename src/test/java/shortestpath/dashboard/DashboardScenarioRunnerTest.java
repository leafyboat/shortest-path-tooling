package shortestpath.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;
import shortestpath.transport.parser.VarCheckType;
import shortestpath.transport.parser.VarRequirement;

/**
 * Exercises {@link DashboardScenarioRunner#apply} — the seam where a scenario's
 * {@code quests} column must reach {@code PathfinderConfig.getQuestState} so
 * quest-gated transports actually gate during pathfinding, and where the
 * {@code bypassVarbitChecks}/{@code bypassVarPlayerChecks} overrides decide
 * whether transport var requirements evaluate at all.
 */
public class DashboardScenarioRunnerTest {

    // Universal bank: every item id 0..24999 in qty 1000 – used for BANK preset runs.
    private static final Item[] UNIVERSAL_BANK_ITEMS;

    static {
        UNIVERSAL_BANK_ITEMS = new Item[25000];
        for (int i = 0; i < 25000; i++) {
            UNIVERSAL_BANK_ITEMS[i] = new Item(i, 1000);
        }
    }

    private Client client;
    private Runnable clientBaseline;
    private ItemContainer universalBankContainer;

    @Before
    public void setUp() {
        client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getClientThread()).thenReturn(Thread.currentThread());
        when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
        when(client.getTotalLevel()).thenReturn(2277);
        when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(1);
        when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getItemContainer(InventoryID.INV)).thenReturn(null);
        when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);

        universalBankContainer = mock(ItemContainer.class);
        when(universalBankContainer.getItems()).thenReturn(UNIVERSAL_BANK_ITEMS);

        // Capture current stub state as the per-scenario baseline Runnable
        clientBaseline = () -> {
            reset(client);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getClientThread()).thenReturn(Thread.currentThread());
            when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
            when(client.getTotalLevel()).thenReturn(2277);
            when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(1);
            when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getItemContainer(InventoryID.INV)).thenReturn(null);
            when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);
        };
    }

    /**
     * A {@code quests=The Grand Tree=NOT_STARTED} cell must reach the
     * pathfinder config: the named quest reports NOT_STARTED while quests
     * absent from the map keep the all-FINISHED default.
     */
    @Test
    public void questsColumnReachesPathfinderConfig() throws IOException {
        Path csv = Files.createTempFile("dashboard-quests", ".csv");
        Files.write(csv, List.of(
            "name,category,start_x,start_y,start_plane,x,y,plane,preset,quests",
            "Grand Tree not started,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,"
                + "The Grand Tree=NOT_STARTED"));
        try {
            DashboardScenario scenario =
                new DashboardScenarioLoader().loadFromCsv(csv).get(0);
            DashboardScenarioRunner.ApplyResult applied = DashboardScenarioRunner.apply(
                scenario, client, clientBaseline, universalBankContainer);
            assertEquals(QuestState.NOT_STARTED,
                applied.pathfinderConfig.getQuestState(Quest.THE_GRAND_TREE));
            assertEquals(QuestState.FINISHED,
                applied.pathfinderConfig.getQuestState(Quest.BONE_VOYAGE));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    /**
     * {@code varPlayerChecks} returns {@code true} when a requirement
     * <em>fails</em> (inverted naming — true means "reject this transport").
     * A {@code config_overrides=bypassVarPlayerChecks=false} row must make
     * transport {@code VarPlayers} requirements evaluate against the
     * scenario's {@code varplayers} stubs — and Mockito's default 0 for
     * unstubbed ids — while an absent override keeps the historical
     * always-bypassed behavior.
     */
    @Test
    public void bypassVarPlayerChecksGatesVarPlayerRequirements() {
        // varp 139 (LEGENDSQUEST progress) appears in the committed transport
        // corpus, so refresh() snapshots varPlayerValues[139] from the client
        // stubs — an id absent from the corpus would never be snapshotted and
        // could not demonstrate the gate.
        Transport varpReqTransport = new Transport.TransportBuilder()
            .varRequirements(Set.of(
                VarRequirement.varPlayer(139, 49, VarCheckType.GREATER)))
            .build();
        int target = WorldPointUtil.packWorldPoint(2971, 2968, 0);

        // Default: bypassed. The stub makes 139>49 fail, yet the requirement
        // must not reject the transport — today's always-bypassed behavior.
        DashboardScenario bypassed = DashboardScenario.builder()
            .preset("UNIT_TEST")
            .endPoint(target)
            .varplayers(Map.of(139, 0))
            .build();
        DashboardScenarioRunner.ApplyResult applied = DashboardScenarioRunner.apply(
            bypassed, client, clientBaseline, universalBankContainer);
        assertFalse(applied.pathfinderConfig.varPlayerChecks(varpReqTransport, 0));

        // bypassVarPlayerChecks=false + a satisfying stub: 50 > 49 passes,
        // so the requirement does not reject the transport.
        DashboardScenario satisfied = DashboardScenario.builder()
            .preset("UNIT_TEST")
            .endPoint(target)
            .varplayers(Map.of(139, 50))
            .configOverrides(Map.of("bypassVarPlayerChecks", "false"))
            .build();
        applied = DashboardScenarioRunner.apply(
            satisfied, client, clientBaseline, universalBankContainer);
        assertFalse(applied.pathfinderConfig.varPlayerChecks(varpReqTransport, 0));

        // bypassVarPlayerChecks=false with no varp-139 stub: Mockito's
        // default 0 fails 139>49, so the transport is rejected — the flag
        // flips the gate and the stub feeds it.
        DashboardScenario unstubbed = DashboardScenario.builder()
            .preset("UNIT_TEST")
            .endPoint(target)
            .configOverrides(Map.of("bypassVarPlayerChecks", "false"))
            .build();
        applied = DashboardScenarioRunner.apply(
            unstubbed, client, clientBaseline, universalBankContainer);
        assertTrue(applied.pathfinderConfig.varPlayerChecks(varpReqTransport, 0));
    }
}
