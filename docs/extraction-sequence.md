# Extraction sequence

The dependency-ordered list of middleware extractions for the plugin: which
subsystem moves when, and what each move touches. Technical order is strict;
upstream PR publication runs on a separate lane gated by review headroom —
integration timing decouples from merge timing. The subsystem inventory
lives in [architecture-survey.md](architecture-survey.md); the target
structure in [architecture-target.md](architecture-target.md).

## Ordering

One row per upstream extraction PR. Blast radius names the files touched,
the harness twins affected, and the re-diff surface; the facts column names
what the row consumes and what it publishes. Sub-issues on the upstream
tracking epic open as each extraction starts.

| Order | Extraction | Upstream sub-issue | Blast radius | Facts consumed / produced |
|-------|------------|--------------------|--------------|---------------------------|
| 1 | Requirement middleware | #715 — landed: #707, #708, #709, #710, #711 | `requirement/` (10 files) + `requirement/model/` (7 files); harness mirrors ride the `RequirementHooks` seam (`TestPathfinderConfig`, `DashboardPathfinderConfig`) | consumes `Client` state via `PlayerStateSource` only; produces the `RequirementContext` snapshot, `RoutingPolicy`, and the gate-verdict contract |
| 2 | Config access & settings | opened with the extraction | `ShortestPathConfig`, `TransportTypeConfig`, the override/config rows on `ShortestPathPlugin` and `PathfinderConfig`; ~19 `override()` call sites and every cached-field consumer re-point; the config panel's read/write/listen contract is pinned here; harness config twins stay `new`-able | consumes `ConfigManager` + the `ShortestPathConfig` interface; produces typed settings views, the panel's write/listen contract, and the "config changed" declaration; absorbs `RoutingPolicy` production and `TeleportationItem` |
| 3 | Player item state | opened with the extraction | `OwnedItems`; the item/bank responsibility rows on `ShortestPathPlugin` and `PathfinderConfig`; the `BankPickupRequirements(PathfinderConfig)` adapter edge; harness twins | consumes container/varbit events via `PlayerStateSource`; produces the carried/bank-path/bank item pools, bank contents, pickup facts, and the "items changed" declaration; absorbs `collectEligibility`/`collectItems` and the `RUNE_POUCH*` constant edges |
| 4 | Spirit trees | opened with the extraction | `SpiritTreePatchState` plus the spirit-tree rows on `ShortestPathPlugin` and `PathfinderConfig`; `Requirements.plantedSpiritTree` re-points at the published set; the five `availableSpiritTrees` write sites collapse | consumes widget scrape + varbit samples + `ConfigManager` persistence; produces the travelable tree set (null = unresolved) and the "tree set changed" declaration; absorbs the patch-tile mapping |
| 5 | POH | opened with the extraction | `PortalNexusKeybinds`, `PohNexusPortal`, `PohMountedItem` plus the `isInsidePoh`/`POH_*`/remap rows on `ShortestPathPlugin` and `PathfinderConfig`; the `Requirements` POH gates move with it; `TransportAvailability` and `PathTileOverlay` retarget — retires the largest inbound-violation set (~16 allowlist sites) | consumes the settings view for portal/item/tier enablement; produces the `isInsidePoh` predicate, landing tile, enablement facts, exit-info display, and the "POH facts changed" declaration; absorbs `JewelleryBoxTier` |
| 6 | `BankVisitState` enum | tracked on the epic — mechanical PR, no subsystem boundary | ~14 files across `pathfinder/` (availability views, `PathStep`, visited/node stores), `requirement/` (`TransportEligibility.usable`), the plugin shell, both backends, and the harness twins | replaces the positional banked/unbanked `boolean` at API surfaces with `BankVisitState.CARRIED`/`BANKED`; internals stay primitive storage encoding |
| 7 | Path scheduler | opened with the extraction | `PendingTask`, `ActiveSearch` plus the executor/mutex/query/target rows on `ShortestPathPlugin`; `Pathfinder`/`ExactPathfinder` construction sites; the `ProfilingPathfinder` harness mirror | consumes engine inputs (`PathfinderConfig`, captured `RequirementContext`); produces the `ActiveSearch` handle and query results |
| 8 | Refresh & invalidation coordinator | opened with the extraction | the refresh-decision rows on `ShortestPathPlugin`; the `refresh()`/`refreshTransports()`/`eligibilityStale` rows on `PathfinderConfig`; ~10 handler bodies collapse to declarations | consumes the producers' "what changed" declarations; sole caller of the scheduler — produces refresh/restart/invalidate decisions |
| 9 | Diagnostics | opened with the extraction — rides the scheduler slot | `DebugState` plus its writer call sites in restart/query paths; `DebugOverlayPanel` re-points to an injected read | consumes scheduler outcomes; produces the `Restart` snapshot, search handles, and error tallies read on the render thread |
| 10 | Plugin-message API + menu verbs + transport presentation | opened with the extraction | the `PLUGIN_MESSAGE_*`/dispatch/parsing/serialization rows, the menu/target row, and the `transportsForEdge`/`formatTransportDisplay`/`getPohExitInfo` rows on `ShortestPathPlugin`; the display half of `BankPickupRequirements` | consumes the scheduler, settings service, POH facts, and item facts; produces the protocol contract, verb delegation, and the shared edge annotator's display strings |
| 11 | Path rendering overlays | opened with the extraction | `PathTileOverlay`, `PathMinimapOverlay`, `PathMapOverlay`, `PathMapTooltipOverlay`, `ArrowHead` — the ~90 `plugin.*` reads re-point, no logic moves | terminal consumer: settings view, `ActiveSearch`, POH/item facts, widget geometry |
| 12 | Highlight overlays | opened with the extraction | `AbstractHighlightOverlay`, `BankItemHighlightOverlay`, `InventoryHighlightOverlay`, `SpellbookHighlightOverlay` | terminal consumer: item-state pickup facts + settings view |
| 13 | TSV data loading | opened with the extraction | `Destination`, `Transport`, `TransportType`, `TransportLoader`, `LoadInterner`, the destination-map rows on `PathfinderConfig`, `LeagueRegionChecker.parse`; wraps `transport/parser/` rather than rebuilding it; retires all five plugin-class resource anchors | consumes classpath resources anchored on its own class; produces immutable transport/destination maps and `bankRequirements` |
| 14 | Widget & UI geometry | opened with the extraction | the ~350-line geometry row on `ShortestPathPlugin` plus the overlay, menu-verb, tree-scrape, and nexus-dialog call sites | consumes `Client` widget/viewport APIs on the client thread; produces world↔graphics point mapping and the minimap clip area |
| 15 | Player skill levels | opened with the extraction | the `PathfinderConfig` skill-layout row, `SkillRequirementParser`, `Requirements.skillLevel`, `Destination`/`DestinationRequirements`, `Transport` | consumes nothing injected — a per-refresh value type; produces the typed extended-index contract (`Skill.values().length + 3`) |

## Ordering rationale — the dependency edges

The order above was hypothesized by the earlier subsystem analysis and is
verified here against the survey's boundary records. Each step names the
concrete edge it rests on, not a restatement of the hypothesis.

| Step | Ordering edge | What the survey shows | Verdict |
|------|---------------|------------------------|---------|
| 1 | Settings first — every downstream service reads config through it | ~19 `override()` call sites (16 in `PathfinderConfig.cacheConfigValues()`, 3 in `TransportTypeConfig`) plus ~20 cached public fields fan out to overlays, the plugin-message `config` payload path, the `TRANSPORT_OPTIONS_REGEX` invalidation, and the panel's read/write/listen contract | Confirmed |
| 2 | Item state and spirit trees second — the snapshot already consumes their facts | `RequirementContext.capture`/`collectEligibility` collects the item pools today and `context.getAvailableSpiritTrees()` is a declared context input — the producers must exist before the context's inputs have owners | Confirmed |
| 3 | POH third — the largest domain consolidation, landed while the middleware stack is still in flight | four `isInsidePoh` call sites inside `Requirements` (`pohDisabled`, `pohVariant`), `TransportAvailability.Builder.remapPohTransports`, six `PathTileOverlay` sites, and the `POH_LANDING_*` static imports; the re-audit folds the three POH gates and `JewelleryBoxTier` into this extraction | Confirmed |
| 4 | Scheduler + coordinator fourth — the coordinator needs producers to hear | the coordinator's declared-facts seam presumes producer services declaring "what changed"; the ~10 `restartPathfinding` call sites collapse to coordinator declarations, and the coordinator is the scheduler's sole caller | Confirmed |
| 5 | API + presentation fifth — it delegates to the scheduler | `queryPath`/`runQuery`/`restartPathfinding`/`setTarget` calls, the `config` payload's write path into the settings service, the menu verbs' scheduler delegation, and presentation reads of `ActiveSearch` + POH/item facts | Confirmed |
| 6 | `BankVisitState` before the scheduler and API/presentation rows | the banked/unbanked `boolean` crosses `pathfinder` ↔ `requirement` ↔ shell ↔ harness twins; nested inside any single subsystem PR it would force the same signatures to be re-diffed twice | Confirmed — standalone row 6 |
| 7 | TSV loader, skills type, widget geometry opportunistic | no inbound hard edges — nothing downstream blocks on them; widget geometry's consumers (menu verbs, overlays, tree scrape, nexus dialogs) make it cheapest before the presentation retarget finishes — a soft preference, not a hard ordering edge | Confirmed with refinement |

## Publication lane

The technical order above is strict — each row builds on seams the rows
above it publish. Merge timing is a separate lane: the upstream maintainer
reviews deliberately, so the next extraction PR is published only as
stack headroom opens rather than the moment its code is ready.

- Every extraction PR stacks on its predecessor upstream. The fork's
  `1.22.0` version-accumulation branch carries all landed and in-flight
  extractions as one integrated branch, so the complete refactor exists
  somewhere testable regardless of upstream merge timing.
- Each upstream extraction PR pairs with a tooling-repo `chore/stage-*`
  branch carrying the submodule pin plus any harness fixes, so dashboard
  generation and `maintenance.py verify` stay green against the in-flight
  head.
- Tooling `master` never carries a submodule pin for unmerged work — a
  pin lands only after the upstream PR merges.

## Per-extraction-PR checklist

Every row above ships as one upstream PR per subsystem and clears the
same gate:

- Zero-delta `maintenance.py verify` before and after the extraction —
  any delta attributed and explained.
- Adversarial tests accompany any requirement/eligibility semantics that
  move (null/empty requirement branches, OR-vs-AND combinations, every
  teleportation-item mode).
- The PR body deep-links its boundary record in
  `architecture-survey.md` at a pinned SHA (`blob/<sha>/docs/...` +
  section anchor), so the reviewed revision stays the referenced one.
- A paired tooling-repo `chore/stage-*` pin PR carries the submodule pin
  and harness fixes; it merges only after the upstream PR does.
- `./gradlew test --tests '*DependencyRule*'` stays green — the frozen
  coupling allowlist only shrinks: entries a row retires leave in the
  same change, and no new leaf-package reference to the plugin shell may
  appear.
- One subsystem per PR. Mechanical cross-cuts (the enum row) are the only
  exception, and they stand alone rather than nesting inside a subsystem.

## Remediation adjudication

Remediation adjudication (2026-10-06): fold — every re-audit finding is a
localized domain leak or placement wrinkle with a named fold target; the
middleware idiom (injected services, immutable snapshot, named gates,
hooks seam) is not defective and no remediation phase is created.

Each finding lands inside the extraction row that redefines its file's
boundary — every file moves at most once more:

- The POH gates' domain logic (`pohDisabled`, `pohVariant`,
  `jewelleryBoxTier`) and the `JewelleryBoxTier` model type fold into the
  POH extraction — the gates keep their verdicts; the POH reading moves.
- The `plantedSpiritTree` patch-tile mapping folds into the spirit-tree
  extraction — the gate keeps reading the published tree set.
- The `leagueRegion` gate's region classification folds into the deferred
  leagues boundary when it extracts; the gate keeps the verdict.
- The `skillLevel` gate's layout/index knowledge folds into the
  `PlayerSkills` value type; the gate stays thin delegation.
- `RoutingPolicy` production (`buildRoutingPolicy`) and the
  `TeleportationItem` mode enum fold into the settings extraction.
- `collectEligibility`/`collectItems` and the `RUNE_POUCH*`/
  `BankPickupRequirements` config edges fold into the item-state
  extraction.
- `BankPickupRequirements` display-phrase building folds into the
  transport-presentation boundary.

Watch items — recorded, no standalone remediation: display-info string
matching as requirement evidence (the `respawn`/`unlockGate` gates), and
policy params living inside `TransportEligibility` alongside the item
pools. Both are observed-noted in the survey; neither corrupts the
zero-delta evidence later extractions produce.

## Tracking

Tracked upstream as [epic #714](https://github.com/Skretzo/shortest-path/issues/714)
on Skretzo/shortest-path — a design record, not a demand. The landed
middleware's sub-issue is [#715](https://github.com/Skretzo/shortest-path/issues/715);
per-subsystem sub-issues join the epic as each extraction opens.
