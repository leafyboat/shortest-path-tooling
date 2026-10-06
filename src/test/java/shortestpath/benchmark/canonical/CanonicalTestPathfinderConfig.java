package shortestpath.benchmark.canonical;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import shortestpath.ShortestPathConfig;
import shortestpath.pathfinder.TestPathfinderConfig;

/** Test-only config that evaluates each exported quest state independently. */
public final class CanonicalTestPathfinderConfig extends TestPathfinderConfig {
    private final Set<String> completedQuestNames;
    private final long benchmarkNowMinutes;

    public CanonicalTestPathfinderConfig(
            Client client, ShortestPathConfig config, Set<String> completedQuestNames,
            long benchmarkNowMinutes) {
        super(client, config, QuestState.NOT_STARTED, false, false);
        this.completedQuestNames = Set.copyOf(completedQuestNames);
        this.benchmarkNowMinutes = benchmarkNowMinutes;
    }

    @Override
    public QuestState getQuestState(Quest quest) {
        return completedQuestNames.contains(quest.getName())
            ? QuestState.FINISHED : QuestState.NOT_STARTED;
    }

    @Override
    protected long currentTimeMinutes() {
        return benchmarkNowMinutes;
    }

    long evaluationTimeMinutes() {
        return currentTimeMinutes();
    }
}
