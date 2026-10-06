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

### Config access & settings

Lives on the god objects today: `ShortestPathConfig` declares the
`@ConfigGroup`/`@ConfigItem` surface, `ShortestPathPlugin` carries the static
`configOverride` map and the typed `override()` overloads, and
`PathfinderConfig.cacheConfigValues()` copies roughly twenty settings into
public fields every refresh.

| Field | Content |
|-------|---------|
| Responsibilities | Typed config access plus the plugin-message override mechanism; `ShortestPathConfig` declarations; `TransportTypeConfig` per-type enablement incl. `disableUnless` derivations; `cacheConfigValues()` cached-field maintenance; `TRANSPORT_OPTIONS_REGEX` deciding which keys invalidate a path |
| Owned state | `configOverride` (volatile `Map<String,Object>`, swapped under `pathfinderMutex`); the ~20 cached public fields on `PathfinderConfig`; per-type enablement inside `TransportTypeConfig` |
| Subscribed events | `ConfigChanged` — today the shell handler re-caches and restarts; post-extraction the coordinator declares "config key changed" to producers instead |
| Published facts | Immutable typed settings views — the `RoutingPolicy` precedent — plus a read/write/listen consumer contract: the config panel writes and observes per key, so read-only views are insufficient |
| Injected dependencies | `ConfigManager` and the `ShortestPathConfig` interface instance (both already injected into the shell) |
| Consumers | ~19 `override()` call sites (16 in `PathfinderConfig.cacheConfigValues()`, 3 in `TransportTypeConfig`); overlay display-field reads (`plugin.colourPath`, `plugin.drawTiles`, `plugin.showTransportInfo`, …); `onConfigChanged`'s `TRANSPORT_OPTIONS_REGEX` match; the plugin-message `config` payload path (`onPluginMessage` → `configOverride` → `cacheConfigValues()`); the panel's `writeConfig`/`registerSync`/`onExternalConfigChanged` |
| Killed seams | Public mutable config-mirror fields on `PathfinderConfig`; stringly-typed `override()` keys scattered across leaf packages; per-site caching duplicated between `cacheConfigValues()` and `TransportTypeConfig` |
| Seam anchors | The settings view's read/write/listen consumer contract is pinned — it is the real crossing both the gates and the panel sit on; the `config` override payload grammar is pinned with the plugin-message protocol record; intra-service method surface provisional |
| Extraction PR | Future — the settings service extraction (first in the sequence; the panel contract shapes it) |
| Blast radius | `ShortestPathConfig`, `TransportTypeConfig`, the override/config responsibility rows on `ShortestPathPlugin` and `PathfinderConfig`; every cached-field consumer re-points; harness twins (`TestPathfinderConfig`, `DashboardPathfinderConfig`) keep `new`-able config |
| Known violations | `TransportTypeConfig` (3 sites) and `PathfinderConfig` (16 sites) call `ShortestPathPlugin.override` — leaf packages reaching into shell statics; migrate to the settings service when it lands |

### Config panel (writer)

`ShortestPathPanel`, `RestrictionListPanel`, `TransportFamilyCard` and
`requirement/TeleportRestriction` exist only on the `feat/config-panel-rework`
branch tip. The panel is the plugin's only config *writer* — a sixth
config-access mechanism alongside reads, overrides, cached fields, per-type
config and the plugin-message `config` payload.

| Field | Content |
|-------|---------|
| Responsibilities | Render and mutate the settings UI: reflection-built `configMethods` map over `@ConfigItem` metadata; the `writeConfig(keyName, value)` funnel through `ConfigManager.setConfiguration`; `suppressConfigSync` echo-guard so panel writes do not re-trigger sync; per-key `registerSync`/`syncControl` observers; `onExternalConfigChanged` re-sync for out-of-panel writes; `TeleportRestriction` is the `blockedTeleportItems` CSV contract it writes |
| Owned state | Presentation residue only — search text, expand/collapse, the owned-items snapshot the restrictions UI renders; no domain state |
| Subscribed events | None directly — the shell forwards `ConfigChanged` into `onExternalConfigChanged` |
| Published facts | The `TeleportRestriction` restriction model (`parseBlocked`/`toCsv`/`loadFamilies`); the per-key write + listen contract the settings service must offer it |
| Injected dependencies | `ConfigManager`, `ShortestPathConfig`, `Client`; reads owned items to annotate restriction rows — an external edge into player item state |
| Consumers | The user, via the plugin panel; `RestrictionListPanel` and `TransportFamilyCard` ride `ShortestPathPanel`'s `writeConfig`/`registerSync`/`suppressConfigSync` |
| Killed seams | Direct `ConfigManager.setConfiguration` calls, reflection over `@ConfigItem` methods, and hand-rolled echo suppression — all absorbed by the settings service's write/listen contract |
| Seam anchors | Both edges external and pinned: config read/write/listen against the settings-service contract; owned-items read against player item state |
| Extraction PR | None dedicated — the revisit folds into the settings extraction's pre-flight; residual Swing glue rides the presentation-boundary work |
| Blast radius | The four panel-branch files only — they never touch the engine or other clusters |
| Known violations | None — the files sit in the top-level package (plus `TeleportRestriction` under `requirement/` on that branch), outside the leaf-package rule |

### Player item state

`OwnedItems` lives under `requirement/` but is owned by this cluster; the
rest is responsibility rows on the god objects — container/varbit handlers,
the `bank` field, `accessibleBankTiles`/`bankRequirements` and the bank-pickup
cache on `ShortestPathPlugin`.

| Field | Content |
|-------|---------|
| Responsibilities | Collect owned-item pools (`OwnedItems.addContainer`/`addRunePouchContents`); track the open bank (`pathfinderConfig.bank` written by `onItemContainerChanged`); invalidate eligibility on container and rune-pouch/diary varbit changes (`invalidateEligibility`, `bankPickupDirty`); own the bank-pickup cache (`bankPickupCache`, `bankPickupCachePath`, `bankPickupCacheIndex`) behind `getBankPickup`; `accessibleBankTiles` + `bankRequirements` decide which tiles flip a path into bank-visited state |
| Owned state | `pathfinderConfig.bank` (`ItemContainer` ref); `bankPickupCache*` fields + `bankPickupDirty`; `accessibleBankTiles`; the carried/bank-path/bank item pools inside `TransportEligibility` (built today by `RequirementContext.collectEligibility`/`collectItems`) |
| Subscribed events | `ItemContainerChanged` (BANK/INV/WORN), `VarbitChanged` filtered to `LUMBRIDGE_DIARY_ELITE_COMPLETE` + `PathfinderConfig.RUNE_POUCH_RUNE_VARBITS`/`RUNE_POUCH_AMOUNT_VARBITS` |
| Published facts | Carried pool, bank-path pool, bank contents, banked rune-pouch contents and the fairy-ring-staff rule — the `TransportEligibility` snapshot shape; `BankPickupResult` phrases + item ids for display; the "items changed" declaration to the coordinator |
| Injected dependencies | `PlayerStateSource` for container/rune-pouch reads (the sole `Client` seam); the bank `ItemContainer` event payload |
| Consumers | `RequirementContext.capture`/`collectEligibility` (the eligibility snapshot); `getBankPickup` callers (`PathTileOverlay` bank-pickup display); `BankPickupRequirements.BankPickupResult.compute` |
| Killed seams | `pathfinderConfig.bank` public field; `bankPickupCache*` plugin fields; eligibility invalidation scattered across shell handlers; the `RequirementContext`/`OwnedItems` reads of `PathfinderConfig.RUNE_POUCHES`/`RUNE_POUCH_RUNE_VARBITS`/`RUNE_POUCH_AMOUNT_VARBITS` constants |
| Seam anchors | Pinned — the item-pool fact set `RequirementContext` consumes is a declared input, and the "items changed" declaration to the coordinator is a real crossing; collection method surface provisional |
| Extraction PR | Future — the player-items producer extraction (lands beside the spirit-tree producer; both feed the context) |
| Blast radius | `OwnedItems`; the item/bank responsibility rows on `ShortestPathPlugin` and `PathfinderConfig`; the `BankPickupRequirements(PathfinderConfig)` adapter edge; harness config twins |
| Known violations | Leaf-to-leaf constant reads, not shell violations: `RequirementContext` imports `PathfinderConfig.RUNE_POUCHES`, `OwnedItems` reads the same constants, and `BankPickupRequirements.compute` takes a `PathfinderConfig` parameter — all migrate with this extraction |

### Spirit trees

`SpiritTreePatchState` is already an `@Singleton` service precedent; the rest
is widget scraping and availability writes in the shell plus the resolved-set
field on `PathfinderConfig`.

| Field | Content |
|-------|---------|
| Responsibilities | Persist and track planted spirit-tree patches (`SpiritTreePatchState`: `notePlayerRegion`, `applyVarbitSample`, `getTravelableTrees`/`getTravelableTreesOrNull`, `loadFromProfile`, `persistIfDirty`, `modalWidgetOpen`, `patchNameForRegion`/`patchNameForTile`, `varbitForPatch`); scrape the spirit-tree menu (`parseSpiritTreeWidget`, `parseSpiritTreeMenuRows`, `SpiritTreeMenuSnapshot`); maintain `availableSpiritTrees` on `PathfinderConfig` (`refreshSpiritTreeAvailability`); the ~5 write sites to `pathfinderConfig.availableSpiritTrees` |
| Owned state | Per-profile persisted patch state and varbit samples inside `SpiritTreePatchState`; the resolved `availableSpiritTrees` set (null = unresolved) |
| Subscribed events | `WidgetLoaded` (`InterfaceID.MENU`/`MENU_NEW` → `parseSpiritTreeWidget`); the region-settled varbit sampling inside `onGameTick`; `RuneScapeProfileChanged` (`loadFromProfile` + set refresh) |
| Published facts | The travelable tree set (or unresolved) consumed by `RequirementContext.capture` → `context.getAvailableSpiritTrees()`; the "tree set changed" declaration to the coordinator (today's writers call `restartPathfinding` directly); `SpiritTreeMenuSnapshot` parse result |
| Injected dependencies | `ConfigManager` (patch persistence — the existing `@Inject` ctor); `Client` for widget/varbit reads on the client thread; widget geometry helpers for menu scraping |
| Consumers | `Requirements.plantedSpiritTree`/`isUnavailablePlantedSpiritTree` (via `SpiritTreePatchState.patchNameForTile` + the context's set); `TransportAvailability` tree availability; `refreshSpiritTreeAvailability` inside `PathfinderConfig` |
| Killed seams | The five scattered `pathfinderConfig.availableSpiritTrees` write sites; direct widget scraping in the shell; restart calls issued by producers |
| Seam anchors | Pinned — the context's spirit-tree input (the `Set<String>` with null-unresolved semantics) and the producer's "tree set changed" declaration to the coordinator; widget-scrape internals provisional |
| Extraction PR | Future — the spirit-tree producer extraction |
| Blast radius | `SpiritTreePatchState` plus the spirit-tree responsibility rows on `ShortestPathPlugin` and `PathfinderConfig`; `Requirements.plantedSpiritTree` re-points at the published fact |
| Known violations | None against the leaf rule — `SpiritTreePatchState` reads `ShortestPathPlugin.CONFIG_GROUP` (top-level, shell-internal) and `Requirements` reads the patch-name mapping leaf-to-leaf, which the spirit-tree boundary formalises rather than invents |

### POH

Player-owned-house knowledge is spread across shell statics, two `transport/`
domain types, a persistence helper and four `isInsidePoh` call sites inside
the middleware's POH gates.

| Field | Content |
|-------|---------|
| Responsibilities | POH region predicate and landing tile (`isInsidePoh`, `POH_MIN_X`/`POH_MAX_X`/`POH_MIN_Y`/`POH_MAX_Y`, `POH_LANDING_X`/`POH_LANDING_Y`); transport remapping (`remapPohDestinations`, `remapPohTransports`); exit-info display (`getPohExitInfo`); nexus keybind persistence (`PortalNexusKeybinds`: `refreshFromDialog`, `putFromDialogLine`, `persistIfDirty`, `loadFromProfile`, `TELENEXUS_CREATE_TELELINE`); `PohNexusPortal`/`PohMountedItem` domain types incl. `fromDisplayInfo`/`fromObjectInfo` parsing; the POH gates currently inside `Requirements` (`pohDisabled`, `pohVariant`, `jewelleryBoxTier` and the `isPohNexusPortalEnabled`/`isPohMountedItemEnabled` helpers) are a recorded domain leak |
| Owned state | `PortalNexusKeybinds` per-profile keybind persistence; the POH bounds/landing constants; enabled-portal/mounted-item/tier policy arrives via `RoutingPolicy` |
| Subscribed events | `ScriptPostFired` (`TELENEXUS_CREATE_TELELINE` dialog line), `WidgetLoaded` (TELENEXUS groups → dialog refresh), `GameTick` (keybind refresh + `persistIfDirty`), `RuneScapeProfileChanged` (`loadFromProfile`) |
| Published facts | The `isInsidePoh` region predicate; the POH landing tile; enabled nexus portals/mounted items/jewellery tier (from the settings view); `getPohExitInfo` display info; the "POH facts changed" declaration to the coordinator |
| Injected dependencies | `ConfigManager` (keybind persistence — the `@Singleton` precedent); the settings view for portal/item/tier enablement; `Client` for dialog widgets |
| Consumers | `Requirements` POH gates (4 `isInsidePoh` call sites), `TransportAvailability.Builder.remapPohTransports`, `PathfinderConfig.remapPohDestinations` + `POH_LANDING_*` reads, `PathTileOverlay` (6 `isInsidePoh` sites + portal display), `getPohExitInfo` callers in tooltip/presentation paths |
| Killed seams | `ShortestPathPlugin.isInsidePoh`/`POH_*` static reads from leaf packages (~16 references across `Requirements`, `TransportAvailability`, `PathTileOverlay`, `PathfinderConfig`); display-info/object-info string matching scattered through the gates |
| Seam anchors | Pinned — the `isInsidePoh` predicate and the nexus-portal/mounted-item enablement contract are real crossings consumed across package lines; remap internals provisional |
| Extraction PR | Future — the POH service extraction |
| Blast radius | `PortalNexusKeybinds`, `PohNexusPortal`, `PohMountedItem` plus the `isInsidePoh`/`POH_*`/remap responsibility rows on `ShortestPathPlugin` and `PathfinderConfig`; `Requirements` POH gates move with it; `TransportAvailability` and `PathTileOverlay` retarget |
| Known violations | The record's own leaf files stay clean; the violations this extraction retires are inbound — `Requirements`, `TransportAvailability`, `PathTileOverlay` and `PathfinderConfig` all reach `ShortestPathPlugin.isInsidePoh`/`POH_LANDING_*` today |

### Path scheduler

Owns the cross-thread machinery that runs searches: the single-thread
executor, the mutex, in-flight queries, deferred work and the published
search handle.

| Field | Content |
|-------|---------|
| Responsibilities | `pathfindingExecutor` (single-thread `ExecutorService` — the displayed path wins over pending queries); `pathfinderMutex`; `queries`/`QueryTask` (in-flight plugin-message query map, guarded by the mutex); `pendingTasks` + `PendingTask` (client-thread deferred work item, tick-scheduled via `PendingTask.check`); `restartPathfinding` overloads (incl. `canReviveFiltered`); `setTarget`/`setTargets`/`setStart`/`marker`; `ActiveSearch` publication — `pathfinder`/`legacyPathfinder`/`exactPathfinder`/`exactRoutingSession` volatile refs behind `getActiveSearch()`; `pathfinderFuture` |
| Owned state | The `queries` map and `pendingTasks` list (mutex-guarded, cross-thread); the volatile `ActiveSearch` handle written on the client/worker boundary and read on the render thread; `PendingTask` named here explicitly — it is the scheduler's deferred-work type, not a loose shell class |
| Subscribed events | None directly — the coordinator invokes it; menu verbs and the plugin-message API call its entry points today |
| Published facts | `ActiveSearch` — the immutable-ish published handle (path, reachability, cancellation) read by overlays and the debug panel on the render thread; query results delivered through `postQueryResult`/`postQueryFailure` callbacks |
| Injected dependencies | `PathfinderConfig` and the captured `RequirementContext` as engine inputs; the executor it constructs |
| Consumers | The coordinator (sole `restartPathfinding` caller in target shape); menu verbs (`setTarget`/`setStart`); the plugin-message API (`queryPath`/`runQuery`, `restartPathfinding("plugin message", …)`); overlays via `getActiveSearch()`; the off-route/target-reached logic in `onGameTick` (`isNearPath`, `reachedDistance`) |
| Killed seams | Executor/mutex/query internals leave the shell; the ~10 scattered `restartPathfinding` call sites in event handlers collapse to coordinator declarations |
| Seam anchors | Pinned — `ActiveSearch` is the render-thread read contract, and `restartPathfinding(reason, start, ends, canReviveFiltered)` is the coordinator's sole call in; query/callback plumbing provisional |
| Extraction PR | Future — the scheduler extraction (lands with the refresh coordinator) |
| Blast radius | `PendingTask`, `ActiveSearch` plus the executor/mutex/query/target responsibility rows on `ShortestPathPlugin`; `Pathfinder`/`ExactPathfinder` construction sites; harness mirrors (`ProfilingPathfinder`) |
| Known violations | None — the violation is inward: everything this cluster owns is scattered across the shell today |

### Diagnostics

`DebugState` is owned here as cross-thread state — the partition already
pairs it with the scheduler's concurrency domain; `DebugOverlayPanel` is this
record's consuming panel only (its presentation seam gets its own record —
one owner per file).

| Field | Content |
|-------|---------|
| Responsibilities | `DebugState` — volatile publication of the last restart attempt (`Restart` snapshot: count/reason/tick/outcome via `restartRequested`/`restartOutcome`), the current and cancelled `ActiveSearch` refs, and client/search error tallies (`clientError*`, `searchError*`) with `DebugState.describe` for outcome text; writers are the restart/query paths (`debugState.restartOutcome("failed: " + DebugState.describe(error))`) |
| Owned state | `DebugState`'s volatile fields — written on client and worker threads, read on the render thread; the immutable `Restart` snapshot exists so renders never see a torn reason/tick/outcome mix |
| Subscribed events | None — written by scheduler/restart call sites, never by RuneLite events |
| Published facts | The `Restart` snapshot, `search`/`cancelledSearch` handles and error counters exposed through `@Getter` — the render-thread read contract |
| Injected dependencies | None beyond `ActiveSearch` references it holds |
| Consumers | `DebugOverlayPanel` (`plugin.getDebugState()` reads on the render thread); the shell's restart/query paths write it |
| Killed seams | Ad-hoc debug state on the plugin; torn multi-field reads across threads |
| Seam anchors | Pinned — the `DebugState` field set is the state-vs-presentation contract the debug-overlay record cross-references |
| Extraction PR | Future — lands alongside the scheduler work that produces most of its facts |
| Blast radius | `DebugState.java` plus its writer call sites in restart/query paths |
| Known violations | None. Recorded note: per-gate rejection tallies rendered in `DebugOverlayPanel` are the cheapest legitimate future consumer of the gate-verdict contract — its own post-milestone PR, not current scope |

### Refresh & invalidation coordination

The decision layer that turns RuneLite events into refresh/restart/invalidate
work — today ~10 handler bodies on the shell plus `PathfinderConfig`'s
refresh orchestration.

| Field | Content |
|-------|---------|
| Responsibilities | The refresh-deciding handlers: `onGameStateChanged` (LOGGING_IN→LOADING→LOGGED_IN queues a `PendingTask` refresh), `onWorldChanged` (world hop → refresh — league mode derives from world type), `onRuneScapeProfileChanged` (keybind/tree profile load + restart), `onConfigChanged` (`TRANSPORT_OPTIONS_REGEX` → restart; `drawDebugPanel`/`pathfinderBackend` side effects), `onItemContainerChanged`/`onVarbitChanged` (eligibility invalidation), `onWidgetLoaded`/`onWidgetClosed`/`onPostClientTick` (tree/nexus/fairy-ring observation), `onScriptPostFired` (nexus keybind line), `onGameTick` (`pendingTasks` drain, off-route/`reachedDistance` restarts); `PathfinderConfig.refresh()`/`refreshTransports()` orchestration and the `eligibilityStale` flag |
| Owned state | `lastGameState`/`lastLastGameState` transition memory; the `pendingTasks` deferral list (shared with the scheduler); `eligibilityStale`; `fairyRingPanelOpen` observation flag |
| Subscribed events | The full `@Subscribe` set above — today subscription and decision live in the same handler bodies |
| Published facts | The "what changed" declaration set producers call: config-key-changed, container-changed, varbit-changed, world-changed, profile-changed, tree-set-changed, nexus-changed, tick-elapsed, target-reached/off-route |
| Injected dependencies | The producer services declaring changes; the scheduler — the coordinator is its sole caller |
| Consumers | `PathfinderConfig.refresh`/`refreshTransports`, `restartPathfinding`, `invalidateEligibility` — the actual work this layer triggers |
| Killed seams | ~10 handler bodies deciding refresh policy inline; the `pendingTasks.add(new PendingTask(tick, pathfinderConfig::refresh))` deferred-refresh idiom; event-ordering assumptions smeared across `onGameTick` |
| Seam anchors | Pinned — the coordinator's declared-facts seam is a real contract crossing: producers declare "what changed" as method calls, and the coordinator is the sole caller of the scheduler |
| Extraction PR | Future — lands with the scheduler extraction |
| Blast radius | The refresh-decision responsibility rows on `ShortestPathPlugin`; the `refresh()`/`refreshTransports()`/`eligibilityStale` rows on `PathfinderConfig` |
| Known violations | None — every site sits in the shell/top-level package today |

### Plugin-message API

The plugin's external input surface: other RuneLite plugins drive it through
namespaced `PluginMessage` events.

| Field | Content |
|-------|---------|
| Responsibilities | The `PLUGIN_MESSAGE_*` protocol constants (`path`, `clear`, `start`, `target`, `config`, `transports`, `query`, `result`, `getTarget`, `currentTarget`, `id`); `onPluginMessage` dispatch gated on the `CONFIG_GROUP` namespace; payload parsing via `parseStart`/`parseTargets`; async queries through `queryPath`/`runQuery`/`QueryTask`; result publication via `postQueryResult`/`postQueryFailure`/`postCurrentTarget`/`postPluginMessages`; the `config` payload → `configOverride` write + `cacheConfigValues()` re-read |
| Owned state | None of its own — query handles belong to the scheduler's `queries`/`QueryTask`; the `config` payload hands off to the override mechanism |
| Subscribed events | `PluginMessage` (namespace-gated to `CONFIG_GROUP`) |
| Published facts | The protocol contract itself: inbound messages `path` (start/target/config payload), `query` (id + start/target), `getTarget` (id echo), `clear`; outbound `transports`, `result`/`currentTarget` payloads and `postQueryFailure` reason strings (`INVALID`, `SHUTDOWN`, `ERROR`) |
| Injected dependencies | The scheduler (`queryPath`, `restartPathfinding`, `setTarget`); the settings service (`configOverride` write path) |
| Consumers | Third-party RuneLite plugins sending `path`/`query`/`config`/`getTarget`/`clear` messages |
| Killed seams | Payload parsing, dispatch and result serialization inline in the shell; the duplicated edge-walk + transport serialization between `postPluginMessages` and `postQueryResult` folds under the shared annotator the presentation record describes |
| Seam anchors | Pinned — the protocol contract (message names + payload field shapes) is the plugin's public API; `parseStart`/`parseTargets` and `config`-payload handling are the plugin's external input-validation seam and the trust boundary for the override mechanism — documented as such, not changed this milestone |
| Extraction PR | Future — the API extraction (lands with the presentation boundary work) |
| Blast radius | The `PLUGIN_MESSAGE_*`/dispatch/parsing/serialization responsibility rows on `ShortestPathPlugin`; `QueryTask` is shared with the scheduler record |
| Known violations | None structurally; the input-validation seam is recorded deliberately — external plugins can push `config` overrides, the only remote write path into the override mechanism |

### Menu & target-setting verbs

The right-click verb layer — a cluster distinct from widget geometry (verbs,
not math): it decides what the user *can* ask for and delegates the rest.

| Field | Content |
|-------|---------|
| Responsibilities | `onMenuEntryAdded` (the SET TARGET/SET START/CLEAR PATH/FIND_CLOSEST option rows, incl. colour-tagged target names), `onMenuOpened`, `addMenuEntry` (menu-entry construction with `onClick` wiring), `onMenuOptionClicked` (verb dispatch → `setTarget`/`setStart`); shift-click on the minimap hit-tested against `getMinimapClipArea` |
| Owned state | The menu option constants and verb semantics; selected-point resolution delegates to widget geometry (`getSelectedWorldPoint`, `calculateMapPoint`) |
| Subscribed events | `MenuEntryAdded`, `MenuOpened`; option clicks arrive through the entry's `onClick` callback |
| Published facts | User intent as verbs — set-target / set-start / clear-path / find-closest — delegated to the scheduler and API in target shape |
| Injected dependencies | The scheduler (`setTarget`, `setStart`, `marker`); widget geometry (`getSelectedWorldPoint`, `getMinimapClipArea`) |
| Consumers | The user via right-click menus and minimap clicks |
| Killed seams | Menu wiring and target mutation inline in the shell |
| Seam anchors | Provisional method surface — but the verb set itself (option strings + semantics) is the contract worth pinning at extraction, since menu placement rules interact with RuneLite's entry ordering |
| Extraction PR | Future — folds into the API/presentation extraction |
| Blast radius | The menu/target responsibility row on `ShortestPathPlugin` |
| Known violations | None |

### Transport presentation

The edge→display-string layer shared by the plugin-message serializers and
the overlays — folds into the API cluster's presentation boundary.

| Field | Content |
|-------|---------|
| Responsibilities | `transportsForEdge` (path-step pair → transports connecting it), `formatTransportDisplay` (transport → display string), `getPohExitInfo` (POH portal exit info for a destination), `BankPickupRequirements` display phrases (`BankPickupResult.phrases`) — the text every presentation surface shares |
| Owned state | None — pure formatting over scheduler output and POH/item facts |
| Subscribed events | None |
| Published facts | Per-edge display strings: `displayInfo` fields in the `transports`/`result` payloads, POH exit info, bank-pickup phrases |
| Injected dependencies | POH facts (`getPohExitInfo`), item state (pickup phrases), the scheduler's `ActiveSearch` path |
| Consumers | `postPluginMessages`, `postQueryResult`; `PathTileOverlay`, `PathMapTooltipOverlay`, `SpellbookHighlightOverlay` (`plugin.transportsForEdge`, `plugin.formatTransportDisplay`, `plugin.getPohExitInfo`) |
| Killed seams | The duplicated edge-walk + display-string serialization between `postPluginMessages` and `postQueryResult`; overlays walking `plugin.transportsForEdge` themselves — a shared annotator ends both |
| Seam anchors | Provisional method surface; the phrase/serialization format is the contract to pin at extraction time |
| Extraction PR | Future — folds into the plugin-message API extraction's presentation boundary |
| Blast radius | The `transportsForEdge`/`formatTransportDisplay`/`getPohExitInfo` responsibility rows on `ShortestPathPlugin`, the display half of `BankPickupRequirements`, and overlay call sites |
| Known violations | `PathTileOverlay` and `SpellbookHighlightOverlay` call `plugin.transportsForEdge`/`formatTransportDisplay`/`getPohExitInfo` — leaf-package instance reads into the shell that this boundary replaces |

### TSV data loading

The `transport/parser/` grammar package is the absorption target; the rows
below are the hand-rolled loaders that fold into it.

| Field | Content |
|-------|---------|
| Responsibilities | `Destination.addDestinations` — a hand-rolled `Scanner` loop over ~20 `destinations/**` resource paths anchored on `ShortestPathPlugin.class.getResourceAsStream`; `Destination.loadBankRequirementsFromResources` — a second `Scanner` loop producing `Map<Integer, DestinationRequirements>`; `Destination.loadAllFromResources` entry point; `Transport` record, `TransportType` taxonomy, `TransportLoader.loadAllFromResources`, `LoadInterner` load-scoped dedup pools; `LeagueRegionChecker.parse` folds in; the destination-map responsibility rows on `PathfinderConfig` (`allDestinations`/`filteredDestinations`, `hasDestination`, `getDestinations`, `filterLocations`, `filterDestinations`) |
| Owned state | The loaded destination maps and bank requirements; interner pools live only for the load |
| Subscribed events | None — data loads eagerly at config build and inside `refresh()` |
| Published facts | Immutable `Transport`/`Destination` data and the per-tile destination index (`getDestinations("bank")` etc.); `bankRequirements` for destination-side requirement checks |
| Injected dependencies | Classpath resources — the extracted loader anchors on its own class, so the plugin-class anchoring idiom does not migrate into a leaf package |
| Consumers | `PathfinderConfig` (transports + destinations), `RequirementContext.capture`/`check(DestinationRequirements)` (bank requirements), `accessibleBankTiles` derivation |
| Killed seams | The two hand-rolled `Scanner` loops in `Destination` replaced by `transport/parser/` machinery; the `ShortestPathPlugin.class.getResourceAsStream` anchors die rather than migrate |
| Seam anchors | Pinned — the resource-path anchors and the produced map shapes (`Map<String, Set<Integer>>` destinations, `Map<Integer, DestinationRequirements>` bank requirements) are the consumed contract; parser internals provisional |
| Extraction PR | Future — the TSV-loader extraction |
| Blast radius | `Destination`, `Transport`, `TransportType`, `TransportLoader`, `LoadInterner`, the destination-map rows on `PathfinderConfig`, `LeagueRegionChecker.parse`; the `transport/parser/` package is wrapped, not rebuilt |
| Known violations | `Destination`'s two `ShortestPathPlugin.class.getResourceAsStream` anchors — top-level file, outside the leaf-package rule, recorded here because this extraction owns their removal |

### Widget & UI geometry

Roughly 350 lines of widget math in the shell — pure client-thread geometry,
shared by menu verbs, overlays, spirit-tree menu scraping and nexus dialogs.

| Field | Content |
|-------|---------|
| Responsibilities | `getMinimapClipArea`/`getMinimapDrawWidget` (minimap shape + hit-test region), `bufferedImageToPolygon`, `mapWorldPointToGraphicsPointX`/`Y` (packed world point → graphics coordinates), `calculateMapPoint` (screen point → world point inverse), `getSelectedWorldPoint` (menu/click position resolution), `scrollFairyRingPanel` (fairy-ring log auto-scroll to the hovered entry) |
| Owned state | `fairyRingPanelOpen` observation flag; otherwise stateless math over `Client` widget APIs |
| Subscribed events | `PostClientTick` (drives `scrollFairyRingPanel` while the fairy-ring log is open); `WidgetLoaded`/`WidgetClosed` maintain the open flag |
| Published facts | World↔graphics point mapping, the minimap clip area — geometry services every presentation surface consumes |
| Injected dependencies | `Client` widget/viewport APIs (client thread) |
| Consumers | Menu verbs (`getSelectedWorldPoint`, minimap hit-test); overlays (`plugin.mapWorldPointToGraphicsPointX/Y`, `plugin.calculateMapPoint` — the dominant `plugin.*` reads on `PathMapOverlay`/`PathMapTooltipOverlay`); `parseSpiritTreeWidget`; nexus dialog refresh |
| Killed seams | ~350 lines of widget math in the shell; overlays reaching `plugin.*` for geometry |
| Seam anchors | Provisional — the geometry function surface is internal until the presentation boundary pins it |
| Extraction PR | Future — the widget-geometry extraction |
| Blast radius | The geometry responsibility row on `ShortestPathPlugin` plus overlay call sites |
| Known violations | `PathMapOverlay`/`PathMapTooltipOverlay` call `plugin.mapWorldPointToGraphicsPointX/Y` and `plugin.calculateMapPoint` — leaf→shell instance reads replaced by the geometry service |

### Player skill levels

No files today — a pending value type. The magic `int[]` layout
(`Skill.values().length + 3`: trailing indices carry total level, combat
level and quest points) is the extended-index contract four sites share.

| Field | Content |
|-------|---------|
| Responsibilities | Own the `Skill.values().length + 3` layout and its index semantics — `Skill.values().length` = total level, +1 = combat level, +2 = quest points; `MAX_LEVEL`/`maximumLevel` sentinels (99 per skill, `99 * Skill.values().length` for total, 126 combat, quest-point cap) — replacing magic indices across all readers |
| Owned state | None — a value type, constructed per refresh/per transport |
| Subscribed events | None |
| Published facts | The extended-index contract itself; typed accessors replacing bare `int[]` reads |
| Injected dependencies | None — produced by `RequirementContext.capture` and the parsers |
| Consumers | `Transport.NO_SKILLS`/`Transport.Builder.skillLevels`, `SkillRequirementParser`, `Destination`'s skills-column parse, `RequirementContext.boostedSkillLevelsAndMore`, `Requirements.skillLevel`/`maximumLevel`, bank `DestinationRequirements` skill sets |
| Killed seams | Bare `int[]` + magic-index arithmetic in four places; the `Requirements` → `SkillRequirementParser.MAX_LEVEL` leaf-to-leaf constant read |
| Seam anchors | Pinned — the `Skill.values().length + 3` index layout is the contract every consumer already shares |
| Extraction PR | Future — the skills value-type extraction |
| Blast radius | `PathfinderConfig`'s skill-layout row, `SkillRequirementParser`, `Requirements.skillLevel`, `Destination`/`DestinationRequirements`, `Transport` |
| Known violations | `Requirements` imports `SkillRequirementParser` for `MAX_LEVEL` — a leaf-to-leaf edge the shared value type absorbs |

### Path rendering overlays

The path-drawing trio plus `ArrowHead` — a presentation-seam record: these
consume the settings service and scheduler output; they own no logic.

| Field | Content |
|-------|---------|
| Responsibilities | `PathTileOverlay` (841 lines — tile-by-tile path drawing, transport info, bank-pickup hint, unreachable text, teleport pulse, tile counter), `PathMinimapOverlay`, `PathMapOverlay`, `PathMapTooltipOverlay`, `ArrowHead` (direction glyph) — render the active search's path across client, minimap and world-map surfaces |
| Owned state | Render state only — no domain state |
| Subscribed events | None — driven by the render loop |
| Published facts | None — terminal consumers |
| Injected dependencies | Today: `plugin.*` (~90+ accesses across the five files — display fields `pathStyle`, `colour*`, `draw*`, `show*`, `tileCounterStep`; state `getActiveSearch`, `nextPathStep`, `isPathUnreachable`, `getBankPickup`, `getPohExitInfo`, `getPathfinderConfig`; geometry `mapWorldPointToGraphicsPointX/Y`, `calculateMapPoint`, `getMinimapClipArea`). Target: narrow read seams — the settings view (display prefs), the scheduler's `ActiveSearch`, item-state pickup facts, POH facts, widget geometry |
| Consumers | The render thread |
| Killed seams | Every `plugin.*` read — replaced by the published facts above |
| Seam anchors | Provisional — the published-facts shape they read is the contract; named inputs are already enumerable (the `plugin.*` symbol set above) |
| Extraction PR | Future — the presentation-boundary work |
| Blast radius | The five overlay files; no logic moves — reads re-point |
| Known violations | `PathTileOverlay` reads `ShortestPathPlugin.isInsidePoh` at 6 sites — leaf→shell static reads that migrate to the POH service; the `plugin.*` instance reads are the seam this boundary exists to kill |

### Highlight overlays

`AbstractHighlightOverlay` plus the three concrete highlighters — the same
presentation-seam shape as the path overlays, scoped to bank-pickup visuals.

| Field | Content |
|-------|---------|
| Responsibilities | `AbstractHighlightOverlay` shared base; `BankItemHighlightOverlay` (bank slots for pickup items), `InventoryHighlightOverlay` (carried items), `SpellbookHighlightOverlay` (teleport spells in the spellbook) — highlight where the player can satisfy the bank-pickup plan |
| Owned state | Render state only |
| Subscribed events | None — render-loop driven |
| Published facts | None — terminal consumers |
| Injected dependencies | Today: `plugin.getPathfinderConfig`, `plugin.highlightSpellbookSpells`, `plugin.colourBankPickupHighlight`, `plugin.getActiveSearch`, `plugin.transportsForEdge`, `plugin.getBankPickup`. Target: item-state pickup facts + settings view |
| Consumers | The render thread |
| Killed seams | `plugin.*` reads; the bank-pickup display logic they wrap |
| Seam anchors | Provisional — consumers of the item-state and settings published facts |
| Extraction PR | Future — the presentation-boundary work |
| Blast radius | The four overlay files |
| Known violations | None static — the `plugin.*` instance reads are the seam this boundary replaces |

### Debug overlay

`DebugOverlayPanel` is the presentation seam for diagnostics — deliberately
split from `DebugState` (state vs presentation; no file is owned by two
records).

| Field | Content |
|-------|---------|
| Responsibilities | Render the diagnostics panel: the last restart attempt (`DebugState.Restart`), the current search state (`renderSearch`/`state(ActiveSearch, ExactPathfinder, PathfinderResult, DebugState)`), `renderRestart`, `renderErrors`; shown/hidden on the `drawDebugPanel` config key |
| Owned state | Panel-side render state only — `DebugState` is an injected data source owned by the diagnostics record (cross-referenced there, not re-owned here) |
| Subscribed events | None — overlay manager registration rides `onConfigChanged`; renders each frame |
| Published facts | None — terminal consumer |
| Injected dependencies | `DebugState` via `plugin.getDebugState()` today — an injected read seam in target shape; `ActiveSearch`/exact-search handles for state text |
| Consumers | The render thread; developers reading the panel |
| Killed seams | `plugin.getDebugState()` shell read → injected `DebugState` |
| Seam anchors | Pinned — reads the `DebugState` snapshot contract the diagnostics record owns |
| Extraction PR | Future — rides the diagnostics/scheduler work |
| Blast radius | `DebugOverlayPanel.java` only |
| Known violations | None. Recorded note: per-gate verdict tallies here are the cheapest legitimate future consumer of the gate-verdict contract — a post-milestone PR, not current scope |

## Coherent units

Units that are already internally coherent get a one-line defer record —
name, files, status, why extraction is deferred, and what would force
re-examination.

- **Pathfinder search core** (`pathfinder/`: `Pathfinder`, `CollisionMap`,
  `NodeGraph`, `VisitedTiles`, `SplitFlagMap`, `IntDeque`, `IntMinHeap`,
  `SearchDeadline`, `WildernessChecker`, `PathStep`, `PathfinderResult`,
  `PathfinderStats`, `PathfinderBackend`, `PathTerminationReason`,
  `AbstractNodeKind`, `OrdinalDirection`, `TransportAvailability`,
  `PathConsumptionValidator`) — confirmed coherent: a tight
  structure-of-arrays engine already behind the `ActiveSearch` handle;
  extracting it now buys nothing the package boundary doesn't already give.
  **Revisit trigger:** a correctness fix needing cross-cluster state, or a
  new mid-search state dimension that breaks the `boolean bankVisited`
  encodings (`PathStep`, `VisitedTiles`, `NodeGraph`).
- **Exact backend** (`pathfinder/exact/` plus the `ExactPathfinder` /
  `ExactRoutingStaticProvider` adapters) — confirmed coherent: the package
  already declares its dependency rule in `package-info.java` — it "must not
  depend on the legacy search implementation or RuneLite's mutable client
  state", the precedent the repo-wide dependency rule copies.
  **Revisit trigger:** the exact backend absorbing legacy call sites or
  growing its own plugin-facing seams beyond `ActiveSearch`.
- **Leagues** (`leagues/`) — confirmed coherent: `LeagueModeState`,
  `LeagueModeSnapshot`, `LeagueRegion`, `LeagueRegionChecker` form a
  self-contained region-gating unit; its TSV parse folds into the loader
  record when the loader extraction lands. **Revisit trigger:** the
  `leagueRegion` gate moving out of `Requirements` into league ownership.
- **Transport TSV parser** (`transport/parser/`) — confirmed coherent: the
  grammar parsers are already a clean leaf under `transport/`.
  **Revisit trigger:** the TSV loader extraction, which should wrap this
  package rather than rebuild it.

## Leaf listing

Stable helper code with no plugin coupling to unwind — partition rows only,
no boundary records.

| File | Cluster | What it is |
|------|---------|------------|
| shortestpath/ItemVariations.java | leaf | item-variation id mapping |
| shortestpath/PrimitiveIntHashMap.java | leaf | primitive int-keyed hash map |
| shortestpath/PrimitiveIntList.java | leaf | primitive int list |
| shortestpath/TileCounter.java | leaf | path tile counter |
| shortestpath/TileStyle.java | leaf | path tile styling enum |
| shortestpath/Util.java | leaf | shared helpers (resource byte reads, misc) |
| shortestpath/WorldPointUtil.java | leaf | packed-int world-point helpers |

## Coupling notes

Facts outside the leaf-package dependency rule that the extraction sequence
consumes — recorded here so they are not lost.

- **`Destination` resource anchors.** `Destination` reads resources via
  `ShortestPathPlugin.class.getResourceAsStream` at Destination.java:63 and
  Destination.java:169 — two of the five plugin-class anchors in the tree.
  The other three are leaf-package sites already frozen in the
  dependency-rule allowlist (`transport/TransportLoader.java:33`,
  `pathfinder/SplitFlagMap.java:92`, `leagues/LeagueRegionChecker.java:105`).
  The `Destination` pair sits outside the leaf-package rule and is owned by
  the TSV loader extraction: the new loader anchors on its own class, so the
  plugin-class idiom dies rather than migrates.
- **Shell-internal group constant.** Top-level shell files read
  `ShortestPathPlugin.CONFIG_GROUP` — `ShortestPathConfig`,
  `PortalNexusKeybinds`, `SpiritTreePatchState`. These are shell-internal
  references, not leaf violations: the dependency rule covers the leaf
  packages only.
- **Leaf-to-leaf requirement reads.** `requirement/` reads
  `PathfinderConfig.RUNE_POUCHES`, `RUNE_POUCH_RUNE_VARBITS`, and
  `RUNE_POUCH_AMOUNT_VARBITS` (`RequirementContext`, `OwnedItems`), and
  `BankPickupRequirements.compute` takes a `PathfinderConfig` parameter.
  Not shell violations, but boundary notes the player-item-state extraction
  owns: the constants and the config edge move with item state.

## Middleware re-audit

The landed requirement middleware, adjudicated against the same boundary
criteria as every other cluster — the exemplar is not exempt. Each finding
carries an explicit disposition for the remediation adjudication: `folds
into` an owning extraction (the file moves at most once more, inside the
extraction that redefines its boundary) or `IDIOM-LEVEL` (a defect in the
snapshot/gates/hooks/package conventions later extractions would copy —
the only trigger that reserves a gated remediation slot).

### Gate classification

The ordered chain in `Requirements` — `check(Transport)` runs all fifteen
gates in fixed order; `check(DestinationRequirements)` reuses the skill,
quest, varbit and varplayer logic for bank destinations.

| Gate | What it reads | Classification | Disposition |
|------|---------------|----------------|-------------|
| `sailing` | `context.isOnSailingBoat` + `type.isTeleport()` | Thin delegation over snapshot facts | Clean — stays |
| `pohDisabled` | `ShortestPathPlugin.isInsidePoh` ×2 on unpacked endpoints | Domain leak — POH region math + shell static inside a leaf package | Folds into the POH extraction |
| `leagueRegion` | `LeagueRegionChecker.getRegion` + `leagueMode.isUnlocked` (+ `getRegionOverride`) | Delegates to league-domain classification | The region classification folds into the leagues boundary when it extracts; the gate keeps the verdict |
| `typeDisabled` | `policy.isTransportTypeEnabled` | Thin delegation over the settings view | Clean — stays |
| `pohVariant` | `isInsidePoh` ×2 + `isPohNexusPortalEnabled` (portal display-info matching) + POH variant toggles | Domain leak — POH semantics + shell static | Folds into the POH extraction |
| `teleportationItem` | `policy.teleportationItemSetting` mode dispatch, `DEADMAN_ONLY_ITEM_IDS`, seasonal-world check, `transport.isConsumable` | Requirement-domain logic; carries the reserved `BLOCKED_ITEM` seat | Stays — the restriction gate fills the reserved seat when the panel's `TeleportRestriction` contract lands |
| `respawn` | `hasDisplayInfo("Respawn")` + `PRIFDDINAS_RESPAWN`/`LUMBRIDGE_RESPAWN` constants + `context.isRespawnPrifddinas` | Requirement-domain logic | Stays — flagged idiom-level note: display-info string matching as requirement evidence (see below) |
| `unlockGate` | `context.getUnlocks` vs pure-unlock `ItemRequirement` branches + `hasDisplayInfo("Honour")` singleton | Requirement-domain logic | Stays — same string-matching note |
| `jewelleryBoxTier` | `PohMountedItem.fromObjectInfo`, `objectInfo.contains("…Jewellery Box <id>")` string matching, `policy.pohJewelleryBoxTier`/`enabledPohMountedItems` | Domain leak — POH domain parsing inside the gate | Folds into the POH extraction (the parsing moves; the gate keeps the verdict) |
| `skillLevel` | `boostedSkillLevelsAndMore` `int[]` loop + `maximumLevel` index table + league-aware total-level skip | Thin delegation carrying the skills-layout contract | The layout/index knowledge folds into the PlayerSkills value type; the gate stays |
| `quest` | `completedQuests` over `context.getQuestStates` | Thin delegation | Clean — stays |
| `varbit` | `hooks.varbitChecks` over the captured varbit map | Thin delegation through the hooks seam | Clean — stays |
| `varplayer` | `hooks.varPlayerChecks` over the captured varplayer map | Thin delegation through the hooks seam | Clean — stays |
| `plantedSpiritTree` | `SpiritTreePatchState.patchNameForTile` + the unresolved-vs-unavailable set semantics | Tree-domain mapping inside the gate | The patch-tile mapping folds into the spirit-tree extraction; the gate keeps reading the published set |
| `itemRequirement` | `eligibility.usable(transport, false)` / `(true)` | Thin delegation over the eligibility snapshot | Clean — the positional `boolean` pair is a seam site for the banked/unbanked domain type |

### `RoutingPolicy` ownership

**Facts.** An eleven-field immutable settings view captured per refresh:
`enabledTypes`, `teleportationItemSetting`, `usePoh`, `usePohFairyRing`,
`usePohSpiritTree`, `usePohObelisk`, `enabledPohNexusPortals`,
`enabledPohMountedItems`, `pohJewelleryBoxTier`, `currencyThreshold`,
`includeBankPath`. Every field is config-derived; seven are POH-specific.
`PathfinderConfig.buildRoutingPolicy` produces it today.

**Verdict.** The type is correctly shaped — an immutable settings view is
exactly what the gates should consume. The question is who owns its
production: the settings service is the natural home, since every field
originates in config and the service already owns typed access. Moving the
type without moving production would move it twice.

**Disposition:** folds into the settings extraction — `buildRoutingPolicy`
(and the type's construction seam) moves to the settings service; the
immutable type itself stays consumed by the gate chain unchanged.

### `RequirementContext` width

**Facts.** The snapshot carries `evaluationTimeMinutes`,
`boostedSkillLevelsAndMore`, `currentMaxQuestPoints`, `questStates`,
`varbitValues`, `varPlayerValues`, `eligibility` (`TransportEligibility` —
embedded carried/bank-path/bank item pools, banked rune-pouch contents, the
fairy-ring-staff rule, plus the policy params `teleportationItemSetting`,
`currencyThreshold`, `unlocks` it evaluates against), `unlocks`,
`respawnPrifddinas`, `isOnSailingBoat`, `leagueModeSnapshot`,
`availableSpiritTrees`. `collectEligibility` is a public static — a residual
seam shared by `capture` and the lazy `getEligibility()` rebuild so exactly
one collection path exists. Two leaf-to-leaf edges remain:
`RequirementContext`/`OwnedItems` read `PathfinderConfig.RUNE_POUCHES`,
`RUNE_POUCH_RUNE_VARBITS`, `RUNE_POUCH_AMOUNT_VARBITS`, and
`BankPickupRequirements.compute` takes a `PathfinderConfig` parameter.

**Verdict.** The width is honest — every field is a real per-refresh input
the gates consume; nothing dead rides the snapshot. The embedded item pools
are the widest edge: they belong to player item state, which publishes them
post-extraction. `TransportEligibility` holding policy params alongside
item pools is a mild state/policy mix — noted, not defective (the pools are
evaluated under that policy; splitting them would complicate the snapshot
for no consumer gain).

**Disposition:** folds into the item-state extraction — `collectEligibility`/
`collectItems` and the `PathfinderConfig` constant edges move to the item
service, which publishes the pools the context keeps consuming. The
policy-inside-eligibility observation is flagged IDIOM-LEVEL-adjacent —
recorded for adjudication, no remediation on its own.

### `model/` consistency

**Facts.** `requirement/model/` holds `DestinationRequirements`,
`ItemRequirement`, `JewelleryBoxTier`, `TransportItems`, `Unlock`,
`VarCheckType`, `VarRequirement` — but `TeleportationItem` (the
teleportation-item mode enum) sits in the engine package root, and
`BankPickupRequirements` produces display phrases — presentation logic —
inside the engine package.

**Verdict.** Placement inconsistency, not a design defect:
`TeleportationItem` is a settings/policy mode (the values describe config
options, not game state); `JewelleryBoxTier` is POH-domain data in the
shared model package; `BankPickupRequirements`' phrase building is the
transport-presentation seam living beside the evaluation it annotates.

**Dispositions:** `TeleportationItem` folds into the settings extraction
(policy type, owned with `RoutingPolicy` production); `JewelleryBoxTier`
folds into the POH extraction; `BankPickupRequirements` display phrases fold
into the transport-presentation boundary. None idiom-level.

### Audit verdict

The exemplar idiom — injected services, immutable per-refresh snapshot,
named stateless gates, hooks seam, package conventions — is clean enough to
copy: every finding above is a localized domain leak or placement wrinkle
with a named fold target, not a defect in the shape itself. No finding
requests the gated remediation slot on its own; the adjudication gate decides
fold-vs-slot with this evidence.
