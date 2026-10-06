# Architectural survey

What the plugin's sources are made of today: a complete census of
`src/main/java` across the stacked upstream branches, the subsystem clusters
those files belong to, and a partition table assigning every file to exactly
one cluster. The target structure lives in
[architecture-target.md](architecture-target.md); the extraction order in
[extraction-sequence.md](extraction-sequence.md).

## Census

The survey covers the four stacked upstream branch tips — the post-middleware
shape of the plugin. The pre-consolidation merge base is not surveyed: the
boundary records describe the shape extraction work actually builds on.

| Branch | Tip | `.java` files under `src/main/java` |
|--------|-----|-----------------------------------|
| `refactor/transport-eligibility` | `40dd35c` | 102 |
| `fix/636-bank-pickup-ledger` | `c692a48` | 102 |
| `fix/636-path-revalidation` | `35d46ba` | 103 |
| `feat/config-panel-rework` | `1d3c71a` | 106 |

Union: **107 unique files**. Branch deltas relative to
`refactor/transport-eligibility`:

- `fix/636-path-revalidation` adds
  `shortestpath/pathfinder/PathConsumptionValidator.java`.
- `feat/config-panel-rework` adds `shortestpath/ShortestPathPanel.java`,
  `shortestpath/RestrictionListPanel.java`,
  `shortestpath/TransportFamilyCard.java`, and
  `shortestpath/requirement/TeleportRestriction.java`.

## Conventions

- **Tiers.** *extraction* — becomes a middleware service with a boundary
  record; *boundary-record* — a presentation seam that gets a record but no
  dedicated service extraction; *deferred* — already coherent, a one-line
  record plus a revisit trigger; *leaf* — listing only. *Landed* means the
  middleware already shipped on the stack; it is audited against the same
  criteria as everything else. *Residual* is what the plugin shell keeps
  after every extraction.
- **Partition.** Every `.java` file under `src/main/java` on the union of the
  four tips — `package-info.java` included — gets exactly one row, keyed to
  the cluster that owns its primary responsibility. A file plausibly
  belonging to two clusters lands in the row for its primary responsibility.
- **Contested files.** `ShortestPathPlugin` and `PathfinderConfig` hold
  several subsystems at once; they get one row per responsibility area, each
  mapped to the cluster that will own it. The file path stays the row's
  first cell with the responsibility qualifier after it — mechanical
  coverage checks read first cells only.
- **Scope.** The partition covers plugin main sources only. Harness and test
  mirrors (`TestPathfinderConfig`, `DashboardPathfinderConfig`,
  `ProfilingPathfinder`) appear as blast radius on the clusters they mirror,
  not as partition rows.
- **Citations.** Symbols, not line numbers — line numbers rot under stack
  rebases. This doc is written once and updated only when the surveyed shape
  changes: a stack rebase moves rows; a landed extraction flips status.

## Cluster index

| # | Cluster | Tier | Files |
|---|---------|------|-------|
| 1 | Config access & overrides | extraction | `ShortestPathConfig`, `TransportTypeConfig` + override/cached-value responsibility rows on the god objects |
| 2 | Config panel (writer) | extraction | `ShortestPathPanel`, `RestrictionListPanel`, `TransportFamilyCard`, `TeleportRestriction` — `feat/config-panel-rework` only |
| 3 | Player item state | extraction | `OwnedItems` + item/bank responsibility rows on the god objects |
| 4 | Spirit trees | extraction | `SpiritTreePatchState` + spirit-tree responsibility rows on the god objects |
| 5 | POH | extraction | `PortalNexusKeybinds`, `PohNexusPortal`, `PohMountedItem` + `isInsidePoh`/`POH_*` responsibility rows |
| 6 | Path scheduler | extraction | `PendingTask`, `ActiveSearch` + executor/mutex/query responsibility rows on `ShortestPathPlugin` |
| 7 | Diagnostics | extraction | `DebugState`, `DebugOverlayPanel` |
| 8 | Refresh & invalidation coordination | extraction | no dedicated files — responsibility rows on the god objects |
| 9 | Plugin-message API | extraction | no dedicated files — responsibility rows on `ShortestPathPlugin` |
| 10 | Menu & target-setting verbs | extraction | no dedicated files — responsibility rows on `ShortestPathPlugin` |
| 11 | Transport presentation | extraction | no dedicated files — responsibility rows on `ShortestPathPlugin` |
| 12 | TSV data loading | extraction | `Destination`, `Transport`, `TransportType`, `TransportLoader`, `LoadInterner` + destination-map rows on `PathfinderConfig` |
| 13 | Widget & UI geometry | extraction | no dedicated files — responsibility rows on `ShortestPathPlugin` |
| 14 | Player skill levels | extraction | no files — pending extraction; the shared `int[]` skill-level layout lives inside `PathfinderConfig`, `SkillRequirementParser`, `Requirements`, `DestinationRequirements` |
| 15 | Path rendering overlays | boundary-record | `PathTileOverlay`, `PathMinimapOverlay`, `PathMapOverlay`, `PathMapTooltipOverlay`, `ArrowHead` |
| 16 | Highlight overlays | boundary-record | `AbstractHighlightOverlay`, `BankItemHighlightOverlay`, `InventoryHighlightOverlay`, `SpellbookHighlightOverlay` |
| 17 | Requirement middleware | landed — audited | `requirement/` (10 files) + `requirement/model/` (7 files) + eligibility-wiring rows on `PathfinderConfig` |
| 18 | Pathfinder search core | deferred | `Pathfinder`, `CollisionMap`, `NodeGraph`, `VisitedTiles`, `SplitFlagMap`, `IntDeque`, `IntMinHeap`, `SearchDeadline`, `WildernessChecker`, `PathStep`, `PathfinderResult`, `PathfinderStats`, `PathfinderBackend`, `PathTerminationReason`, `AbstractNodeKind`, `OrdinalDirection`, `TransportAvailability`, `PathConsumptionValidator` |
| 19 | Exact backend | deferred | `pathfinder/exact/` (20 files) + `ExactPathfinder`, `ExactRoutingStaticProvider` adapters + the account-snapshot row on `PathfinderConfig` |
| 20 | Leagues | deferred | `leagues/` (4 files) |
| 21 | Transport TSV parser | deferred | `transport/parser/` (8 files) |
| 22 | Leaf utilities | leaf | `WorldPointUtil`, `Util`, `PrimitiveIntHashMap`, `PrimitiveIntList`, `ItemVariations`, `TileCounter`, `TileStyle` |
| 23 | Plugin shell residue | residual | lifecycle ordering, `@Subscribe` forwarding, overlay/key-listener registration rows on `ShortestPathPlugin` |

## Partition table

Every census file in exactly one row, sorted by repo path. Contested files
carry one row per responsibility area; stack-only files name their owning
branch.

| File | Cluster | Notes |
|------|---------|-------|
| shortestpath/DebugState.java | diagnostics | cross-thread state owned with the scheduler |
| shortestpath/Destination.java | TSV data loading | hand-rolled `Scanner` parse loops + resource anchors — see coupling notes |
| shortestpath/ItemVariations.java | leaf utilities | leaf listing |
| shortestpath/PendingTask.java | path scheduler | deferred-work item owned by the scheduler |
| shortestpath/PortalNexusKeybinds.java | POH | nexus keybind persistence |
| shortestpath/PrimitiveIntHashMap.java | leaf utilities | leaf listing |
| shortestpath/PrimitiveIntList.java | leaf utilities | leaf listing |
| shortestpath/RestrictionListPanel.java | config panel | `feat/config-panel-rework` only |
| shortestpath/ShortestPathConfig.java | config access & overrides | `@ConfigGroup` + all `@ConfigItem` declarations |
| shortestpath/ShortestPathPanel.java | config panel | `feat/config-panel-rework` only |
| shortestpath/ShortestPathPlugin.java (override/configOverride statics, `CONFIG_GROUP`) | config access & overrides | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (item container and varbit handlers, bank-pickup cache, `getBankPickup`) | player item state | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (spirit-tree widget parse and availability writes) | spirit trees | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (`isInsidePoh`, `POH_*` statics, `remapPohDestinations`/`remapPohTransports`, `getPohExitInfo`) | POH | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (executor, `pathfinderMutex`, `queries`/`QueryTask`, `pendingTasks`, `restartPathfinding`, `setTarget`/`setStart`/`marker`) | path scheduler | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (refresh/invalidation decision handlers — `onGameStateChanged`, `onWorldChanged`, `onRuneScapeProfileChanged`, `onConfigChanged`, container/varbit triggers) | refresh & invalidation coordination | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (`PLUGIN_MESSAGE_*` protocol, `onPluginMessage`, `parseStart`/`parseTargets`, `queryPath`/`runQuery`, `postQueryResult`/`postQueryFailure`/`postCurrentTarget`, `postPluginMessages`) | plugin-message API | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (`onMenuEntryAdded`, `onMenuOpened`, `addMenuEntry`, `onMenuOptionClicked`) | menu & target-setting verbs | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (`transportsForEdge`, `formatTransportDisplay`) | transport presentation | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (`getMinimapClipArea`, `getMinimapDrawWidget`, `bufferedImageToPolygon`, `mapWorldPointToGraphicsPointX`/`Y`, `calculateMapPoint`, `getSelectedWorldPoint`, `scrollFairyRingPanel`) | widget & UI geometry | contested — responsibility row |
| shortestpath/ShortestPathPlugin.java (plugin lifecycle ordering, overlay/key-listener registration, event forwarding that stays) | plugin shell residue | contested — responsibility row |
| shortestpath/SpiritTreePatchState.java | spirit trees | `@Singleton` patch-state service |
| shortestpath/TileCounter.java | leaf utilities | leaf listing |
| shortestpath/TileStyle.java | leaf utilities | leaf listing |
| shortestpath/TransportFamilyCard.java | config panel | `feat/config-panel-rework` only |
| shortestpath/Util.java | leaf utilities | leaf listing |
| shortestpath/WorldPointUtil.java | leaf utilities | leaf listing |
| shortestpath/leagues/LeagueModeSnapshot.java | leagues | deferred unit |
| shortestpath/leagues/LeagueModeState.java | leagues | deferred unit |
| shortestpath/leagues/LeagueRegion.java | leagues | deferred unit |
| shortestpath/leagues/LeagueRegionChecker.java | leagues | deferred unit |
| shortestpath/overlay/AbstractHighlightOverlay.java | highlight overlays | presentation seam |
| shortestpath/overlay/ArrowHead.java | path rendering overlays | presentation seam |
| shortestpath/overlay/BankItemHighlightOverlay.java | highlight overlays | presentation seam |
| shortestpath/overlay/DebugOverlayPanel.java | diagnostics | pairs with `DebugState` |
| shortestpath/overlay/InventoryHighlightOverlay.java | highlight overlays | presentation seam |
| shortestpath/overlay/PathMapOverlay.java | path rendering overlays | presentation seam |
| shortestpath/overlay/PathMapTooltipOverlay.java | path rendering overlays | presentation seam |
| shortestpath/overlay/PathMinimapOverlay.java | path rendering overlays | presentation seam |
| shortestpath/overlay/PathTileOverlay.java | path rendering overlays | presentation seam |
| shortestpath/overlay/SpellbookHighlightOverlay.java | highlight overlays | presentation seam |
| shortestpath/pathfinder/AbstractNodeKind.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/ActiveSearch.java | path scheduler | the scheduler's published handle |
| shortestpath/pathfinder/CollisionMap.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/ExactPathfinder.java | exact backend | `ActiveSearch` adapter for the exact core |
| shortestpath/pathfinder/ExactRoutingStaticProvider.java | exact backend | builds static routing data for the exact core |
| shortestpath/pathfinder/IntDeque.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/IntMinHeap.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/NodeGraph.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/OrdinalDirection.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/PathConsumptionValidator.java | pathfinder search core | `fix/636-path-revalidation` only |
| shortestpath/pathfinder/PathStep.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/PathTerminationReason.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/Pathfinder.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/PathfinderBackend.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/PathfinderConfig.java (cached config values and `override()` reads in `refresh()`) | config access & overrides | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (item collection, `bank`, `accessibleBankTiles`, `bankRequirements`) | player item state | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (`availableSpiritTrees` field) | spirit trees | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (`refresh()`/`refreshTransports()` orchestration, `eligibilityStale`) | refresh & invalidation coordination | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (`allDestinations`/`filteredDestinations`, `hasDestination`, `getDestinations`, `filterLocations`, `filterDestinations`) | TSV data loading | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (`boostedSkillLevelsAndMore` `int[]` layout) | player skill levels | contested — responsibility row; the value type the layout becomes |
| shortestpath/pathfinder/PathfinderConfig.java (availability views: `getTransportsPacked`, `getUsableTeleports`, `getTransportAvailability`, `TransportAvailabilities`) | pathfinder search core | contested — responsibility row; engine inputs |
| shortestpath/pathfinder/PathfinderConfig.java (`requirements`/`eligibility`/`requirementHooks` wiring, `buildRoutingPolicy`) | requirement middleware | contested — responsibility row |
| shortestpath/pathfinder/PathfinderConfig.java (`prepareExactRoutingAccount`) | exact backend | contested — responsibility row |
| shortestpath/pathfinder/PathfinderResult.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/PathfinderStats.java | pathfinder search core | deferred unit; consumed by diagnostics |
| shortestpath/pathfinder/SearchDeadline.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/SplitFlagMap.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/TransportAvailability.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/VisitedTiles.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/WildernessChecker.java | pathfinder search core | deferred unit |
| shortestpath/pathfinder/exact/ExactCosts.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ExactForwardSearch.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ExactMinHeap.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ExactRoute.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ExactRoutingSession.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ExactWalkCanonicalizer.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/PreparedHeuristic.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/PreparedRoutingAccount.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/PreparedTarget.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/ReverseLabels.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/RoutingCuts.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/RoutingStatic.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/RoutingStaticBuilder.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/SearchRestrictions.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/SiteGraph.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/SparseWalkingNetworkBuilder.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/TargetOverlay.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/TeleportCapability.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/WalkGoal.java | exact backend | deferred unit |
| shortestpath/pathfinder/exact/package-info.java | exact backend | declared dependency-rule precedent |
| shortestpath/requirement/BankPickupRequirements.java | requirement middleware | bank-pickup adapter; display phrases ride the presentation seam |
| shortestpath/requirement/ClientPlayerStateSource.java | requirement middleware | `Client`-backed `PlayerStateSource` |
| shortestpath/requirement/OwnedItems.java | player item state | collects owned-item pools; lives in `requirement/` today, owned by item state |
| shortestpath/requirement/PlayerStateSource.java | requirement middleware | the sole `Client` read seam |
| shortestpath/requirement/RejectionReason.java | requirement middleware | gate-verdict enum (19 constants) |
| shortestpath/requirement/RequirementContext.java | requirement middleware | immutable per-refresh snapshot |
| shortestpath/requirement/RequirementHooks.java | requirement middleware | test/harness bypass seam |
| shortestpath/requirement/Requirements.java | requirement middleware | ordered stateless gate chain |
| shortestpath/requirement/RoutingPolicy.java | requirement middleware | config-derived settings view |
| shortestpath/requirement/TeleportRestriction.java | config panel | `feat/config-panel-rework` only; restriction contract the panel writes |
| shortestpath/requirement/TeleportationItem.java | requirement middleware | teleportation-item domain type |
| shortestpath/requirement/TransportEligibility.java | requirement middleware | per-transport eligibility + consumption ledger |
| shortestpath/requirement/model/DestinationRequirements.java | requirement middleware | requirement model |
| shortestpath/requirement/model/ItemRequirement.java | requirement middleware | requirement model |
| shortestpath/requirement/model/JewelleryBoxTier.java | requirement middleware | requirement model |
| shortestpath/requirement/model/TransportItems.java | requirement middleware | requirement model |
| shortestpath/requirement/model/Unlock.java | requirement middleware | requirement model |
| shortestpath/requirement/model/VarCheckType.java | requirement middleware | requirement model |
| shortestpath/requirement/model/VarRequirement.java | requirement middleware | requirement model |
| shortestpath/transport/LoadInterner.java | TSV data loading | load-scoped dedup pools |
| shortestpath/transport/PohMountedItem.java | POH | POH domain type |
| shortestpath/transport/PohNexusPortal.java | POH | POH domain type |
| shortestpath/transport/Transport.java | TSV data loading | the record the loader produces |
| shortestpath/transport/TransportLoader.java | TSV data loading | `loadAllFromResources` entry |
| shortestpath/transport/TransportType.java | TSV data loading | transport-type taxonomy |
| shortestpath/transport/TransportTypeConfig.java | config access & overrides | per-type config + `override()` call sites |
| shortestpath/transport/parser/FieldParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/ItemRequirementParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/QuestParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/SkillRequirementParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/TransportRecord.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/TsvParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/VarRequirementParser.java | transport TSV parser | deferred unit |
| shortestpath/transport/parser/WorldPointParser.java | transport TSV parser | deferred unit |

## Boundary records

One record per extraction candidate follows this fixed schema —
responsibilities, owned state, subscribed events, published facts, injected
dependencies, consumers, killed seams, seam anchors, extraction PR, blast
radius — plus a known-violations row. Seam anchors pin only real contract
crossings; intra-service method surfaces stay provisional until extraction.
Each extraction PR body deep-links its record verbatim.

### Requirement middleware (`shortestpath.requirement`)

The landed exemplar — upstream PR stack #707 → #708 → #709/#710, with #711
carrying the config panel. Audited against the same criteria as every other
cluster.

| Field | Content |
|-------|---------|
| Responsibilities | `Requirements` — ordered stateless gate chain producing `RejectionReason` verdicts; `RequirementContext` — immutable per-refresh player-state snapshot; `RoutingPolicy` — config-derived settings view; `PlayerStateSource`/`ClientPlayerStateSource` — the sole `Client` read seam; `RequirementHooks` — harness bypass seam; `TransportEligibility` — per-transport eligibility incl. the consumption ledger; `TeleportationItem`, `BankPickupRequirements`, `RejectionReason`, `model/` value types |
| Owned state | None — gates are stateless functions of the snapshot; `RequirementContext` is immutable and rebuilt each refresh |
| Subscribed events | None — the plugin shell forwards; the middleware never sees RuneLite events directly |
| Published facts | `RequirementContext` via `@Getter`, populated by the single `capture(...)` entry point; `RoutingPolicy`; package-private `check(Transport)`/`check(DestinationRequirements)` verdicts (`RejectionReason`, 19 constants) |
| Injected dependencies | `PlayerStateSource` (the only `Client` reader — `ClientPlayerStateSource` fails loudly off the client thread); `RequirementHooks` test seam |
| Consumers | `PathfinderConfig.refreshTransports`; the `BankPickupRequirements` adapter; test/dashboard harnesses through `RequirementHooks` |
| Killed seams | Gates no longer read `Client`, `ShortestPathConfig`, or `PathfinderConfig` directly; per-gate config reads collapsed into `RoutingPolicy`; eligibility evaluation centralised in `TransportEligibility` |
| Seam anchors | the `RejectionReason` constant set; `check(...)` stays package-private — verdicts are internal instrumentation, consumed by tests only for this milestone; `RequirementContext.capture(...)` is the producer contract |
| Extraction PR | upstream #707, #708, #709, #710 (landed); #711 carries the config panel and the restriction contract |
| Blast radius | `requirement/` (10 files) + `requirement/model/` (7 files); harness mirrors ride the `RequirementHooks` seam (`TestPathfinderConfig`, `DashboardPathfinderConfig`) |
| Known violations | `Requirements` calls `ShortestPathPlugin.isInsidePoh` four times inside the POH gates (`pohDisabled`, `pohVariant`) — a leaf package referencing the shell; migrates to the POH service when it lands |

Package neighbours deliberately outside this cluster: `OwnedItems` collects
player items and is partitioned to player item state; `TeleportRestriction`
is the config panel's write contract.
