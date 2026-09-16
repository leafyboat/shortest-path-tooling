package shortestpath.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Test;

/**
 * Pure-parse coverage for the {@code quests} scenario column:
 * {@code Quest Name=STATE;…} tokens resolve by exact {@link Quest#getName()}
 * equality and every malformed token aborts the load with an
 * {@link IllegalArgumentException} naming the column and the token.
 */
public class DashboardScenarioLoaderTest {

    private static final String HEADER =
        "name,category,start_x,start_y,start_plane,x,y,plane,preset,quests";
    private static final String HEADER_NO_QUESTS =
        "name,category,start_x,start_y,start_plane,x,y,plane,preset";

    private static DashboardScenario loadOne(String header, String row) throws IOException {
        Path csv = Files.createTempFile("scenario-loader", ".csv");
        Files.write(csv, List.of(header, row));
        try {
            return new DashboardScenarioLoader().loadFromCsv(csv).get(0);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    public void parsesQuestStateMap() throws IOException {
        DashboardScenario scenario = loadOne(HEADER,
            "multi,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,"
                + "The Grand Tree=NOT_STARTED;Monkey Madness II=FINISHED");
        Map<Quest, QuestState> states = scenario.getQuestStates();
        assertEquals(2, states.size());
        assertEquals(QuestState.NOT_STARTED, states.get(Quest.THE_GRAND_TREE));
        assertEquals(QuestState.FINISHED, states.get(Quest.MONKEY_MADNESS_II));
    }

    @Test
    public void rejectsUnknownQuestName() throws IOException {
        try {
            loadOne(HEADER,
                "bad,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,Not A Quest=FINISHED");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("quests"));
            assertTrue(e.getMessage(), e.getMessage().contains("Not A Quest"));
        }
    }

    @Test
    public void rejectsUnknownQuestState() throws IOException {
        try {
            loadOne(HEADER,
                "bad,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,The Grand Tree=DONE");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("quests"));
            assertTrue(e.getMessage(), e.getMessage().contains("The Grand Tree=DONE"));
        }
    }

    @Test
    public void rejectsBareQuestName() throws IOException {
        // A bare name would silently mean FINISHED — already the default — so
        // it must fail fast rather than look meaningful while changing nothing.
        try {
            loadOne(HEADER,
                "bad,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,The Grand Tree");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("quests"));
            assertTrue(e.getMessage(), e.getMessage().contains("The Grand Tree"));
        }
    }

    @Test
    public void absentQuestsColumnDefaultsEmpty() throws IOException {
        DashboardScenario scenario = loadOne(HEADER_NO_QUESTS,
            "none,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST");
        assertTrue(scenario.getQuestStates().isEmpty());
    }

    @Test
    public void apostropheQuestNamesParse() throws IOException {
        DashboardScenario scenario = loadOne(HEADER,
            "apos,quest-gating,3284,3213,0,2971,2968,0,UNIT_TEST,"
                + "Twilight's Promise=IN_PROGRESS");
        assertEquals(QuestState.IN_PROGRESS,
            scenario.getQuestStates().get(Quest.TWILIGHTS_PROMISE));
    }
}
