package shortestpath.dashboard;

import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import shortestpath.ShortestPathConfig;
import shortestpath.pathfinder.TestPathfinderConfig;

/**
 * A {@link TestPathfinderConfig} that answers {@link #getQuestState} from a
 * per-quest map instead of one blanket state, so a scenario can gate
 * individual quest-locked transports while every unmapped quest stays
 * {@link QuestState#FINISHED} (the historical harness default).
 * <p>
 * The superclass constructor still takes a blanket {@code QuestState} — it is
 * vestigial here because the override below supersedes it; we pass
 * {@code FINISHED} so the two models agree. One virtual override feeds every
 * quest consumer in {@code PathfinderConfig}: the {@code refreshTransports}
 * snapshot that powers {@code completedQuests} (per-transport quest locks),
 * the transport-type gates (Grand Tree → gnome gliders, Bone Voyage →
 * mushtrees, Tree Gnome Village → spirit trees), and bank-destination
 * requirements.
 * <p>
 * Hand-rolled rather than Mockito for the same reason as the superclass:
 * {@code getQuestState} runs inside the hot pathfinding loop.
 */
public class DashboardQuestConfig extends TestPathfinderConfig {

    /** Per-quest state overrides: quest → state (absent → FINISHED). */
    private final Map<Quest, QuestState> questStates;

    public DashboardQuestConfig(Client client, ShortestPathConfig config,
            Map<Quest, QuestState> questStates,
            boolean bypassVarbitChecks, boolean bypassVarPlayerChecks) {
        super(client, config, QuestState.FINISHED, bypassVarbitChecks, bypassVarPlayerChecks);
        this.questStates = questStates;
    }

    @Override
    public QuestState getQuestState(Quest quest) {
        return questStates.getOrDefault(quest, QuestState.FINISHED);
    }
}
