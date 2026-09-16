package shortestpath.dashboard;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises {@link DashboardScenarioRunner#apply} — the seam where a scenario's
 * {@code quests} column must reach {@code PathfinderConfig.getQuestState} so
 * quest-gated transports actually gate during pathfinding.
 */
public class DashboardScenarioRunnerTest {

    private Client client;
    private Runnable clientBaseline;
    private ItemContainer universalBankContainer;

    @Before
    public void setUp() {
        client = mock(Client.class);
        universalBankContainer = mock(ItemContainer.class);
        clientBaseline = () -> {
            reset(client);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getClientThread()).thenReturn(Thread.currentThread());
            when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
            when(client.getTotalLevel()).thenReturn(2277);
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
}
