# Target architecture

Where the plugin is headed: a service graph of injected singletons reading
immutable per-refresh snapshots, with the plugin shell thinned to lifecycle
ordering and event forwarding. The current-state inventory lives in
[architecture-survey.md](architecture-survey.md); the order of moves in
[extraction-sequence.md](extraction-sequence.md).

## Layering rules

The target shape is a pragmatic Guice service graph, not a strict layer
cake. Every extraction copies the same rules:

- Services constructor-inject each other's narrow read interfaces. Pulling
  an immutable snapshot is the published-facts mechanism — the
  `RequirementContext` precedent: facts are values, not events.
- Change notification flows inward only. Producers declare "what changed"
  to the refresh coordinator as method calls; the coordinator is the sole
  caller of the path scheduler. No service reaches sideways to trigger a
  sibling's recompute.
- The plugin shell thins to lifecycle ordering, one-line `@Subscribe`
  event forwarders, and overlay/key-listener registration. Menu verbs and
  plugin-message handling delegate to their own services.
- Construction: `@Singleton`/`@Inject` for every plugin-lifetime service —
  Guice fails fast on constructor cycles, which makes the dependency graph
  self-enforcing. `new` for per-refresh objects (`Requirements`,
  `RequirementContext`) and for anything the harnesses subclass —
  `PathfinderConfig` must stay `new`-able for `TestPathfinderConfig` and
  `DashboardPathfinderConfig`. The `SpiritTreePatchState` package-private
  test constructor is the precedent for test-only construction seams.
- Every service and published fact carries its producer-thread and
  consumer-thread pair (client / worker / render) — annotated on the
  diagram below. `PlayerStateSource` stays the only `Client` reader and
  fails loudly off the client thread.

### Rejected alternatives

- **Strict layered decomposition** — every call would route through the
  layer below, so the refresh coordinator ends up brokering all
  cross-service traffic and becomes the next god object. Constructor
  injection of narrow interfaces gives the same isolation without the
  bottleneck.
- **Self-subscribing services** — each service registering directly on the
  RuneLite event bus reintroduces implicit tick ordering; today's handler
  sequence (snapshot capture before invalidation, scheduler calls last) is
  deliberate and would become unreviewable if it were smeared across
  subscriber registration order.
- **Internal domain-event bus** — an invented publish/subscribe layer adds
  infrastructure nothing else needs and confuses the thread story (which
  thread publishes, which consumes). The client already has an event bus;
  services consuming it through a second bus gain nothing.

## Dependency rule

Declared rule, in the same register the exact backend's `package-info`
already uses:

> Leaf packages — `transport/`, `pathfinder/`, `requirement/`, `leagues/`,
> `overlay/` — must not depend on `ShortestPathPlugin`. Cross-cutting
> configuration and world-geometry access goes through owned services (the
> settings service, the POH service) or leaf utilities. All new coupling
> arrives through an injected seam.

The rule covers the leaf packages only. Top-level files sit outside it:
`Destination`'s two `ShortestPathPlugin.class.getResourceAsStream` anchors
and the `CONFIG_GROUP` reads on `ShortestPathConfig`,
`PortalNexusKeybinds` and `SpiritTreePatchState` are recorded in the
survey's coupling notes and owned by the loader and settings extractions.

Today's violations are frozen in an allowlist — every leaf-package
`ShortestPathPlugin.` reference site with the migration that owns its
removal. Extraction work removes entries; entries are never added:

| Site | What it is | Owning migration |
|------|------------|------------------|
| `transport/TransportLoader.java:33` | `ShortestPathPlugin.class` resource anchor — transport TSV read | TSV data loading — the loader anchors on its own class |
| `pathfinder/SplitFlagMap.java:92` | `ShortestPathPlugin.class` resource anchor — collision-map read | TSV data loading — the loader anchors on its own class |
| `leagues/LeagueRegionChecker.java:105` | `ShortestPathPlugin.class` resource anchor — league-region read | TSV data loading — the loader anchors on its own class |
| `pathfinder/PathfinderConfig.java:31` | `import static` of `POH_LANDING_X` | POH service |
| `pathfinder/PathfinderConfig.java:32` | `import static` of `POH_LANDING_Y` | POH service |
| `pathfinder/TransportAvailability.java:9` | `import static` of `POH_LANDING_X` | POH service |
| `pathfinder/TransportAvailability.java:10` | `import static` of `POH_LANDING_Y` | POH service |
| `transport/TransportTypeConfig.java:66` | `override()` read of `useTeleportationItems` | Settings service |
| `transport/TransportTypeConfig.java:109` | `override()` read of a per-type config value | Settings service |
| `transport/TransportTypeConfig.java:125` | `override()` read of a per-type config value | Settings service |
| `pathfinder/PathfinderConfig.java:324` | `override()` read of `unreachableTargetDistanceThreshold` | Settings service |
| `pathfinder/PathfinderConfig.java:327` | `override()` read of `exactHeuristicWeight` | Settings service |
| `pathfinder/PathfinderConfig.java:328` | `override()` read of `avoidWilderness` | Settings service |
| `pathfinder/PathfinderConfig.java:329` | `override()` read of `usePoh` | Settings service |
| `pathfinder/PathfinderConfig.java:335` | `override()` read of `usePohFairyRing` | Settings service |
| `pathfinder/PathfinderConfig.java:336` | `override()` read of `usePohSpiritTree` | Settings service |
| `pathfinder/PathfinderConfig.java:337` | `override()` read of `usePohObelisk` | Settings service |
| `pathfinder/PathfinderConfig.java:341` | `override()` read of `pohJewelleryBoxTier` | Settings service |
| `pathfinder/PathfinderConfig.java:344` | `override()` read of `currencyThreshold` | Settings service |
| `pathfinder/PathfinderConfig.java:349` | `override()` read of `includeBankPath` | Settings service |
| `pathfinder/PathfinderConfig.java:352` | `override()` read of `respawnPrifddinas` | Settings service |
| `pathfinder/PathfinderConfig.java:356` | `override()` read of `unlockCanoeAxe` | Settings service |
| `pathfinder/PathfinderConfig.java:360` | `override()` read of `unlockXericsHonour` | Settings service |
| `pathfinder/PathfinderConfig.java:364` | `override()` read of `unlockDragontoothPassage` | Settings service |
| `pathfinder/PathfinderConfig.java:371` | `override()` read of `costConsumableTeleportationItems` | Settings service |
| `pathfinder/PathfinderConfig.java:372` | `override()` read of `costBankVisit` | Settings service |
| `pathfinder/PathfinderConfig.java:660` | `isInsidePoh` redirect filter | POH service |
| `pathfinder/TransportAvailability.java:100` | `isInsidePoh` origin check | POH service |
| `requirement/Requirements.java:194` | `isInsidePoh` calls inside the `pohDisabled` gate (two on one line) | POH service |
| `requirement/Requirements.java:262` | `isInsidePoh` calls inside the `pohVariant` gate (two on one line) | POH service |
| `overlay/PathTileOverlay.java:251` | `isInsidePoh` marker filter | POH service |
| `overlay/PathTileOverlay.java:286` | `isInsidePoh` tracer filter | POH service |
| `overlay/PathTileOverlay.java:321` | `isInsidePoh` marker filter | POH service |
| `overlay/PathTileOverlay.java:365` | `isInsidePoh` marker filter | POH service |
| `overlay/PathTileOverlay.java:763` | `isInsidePoh` transport-tile check | POH service |
| `overlay/PathTileOverlay.java:764` | `isInsidePoh` player-tile check | POH service |

The exemplar is honest on the same terms: `Requirements`' four
`isInsidePoh` calls sit on the two allowlisted lines above and migrate
with the POH extraction like every other site.

Enforcement is `PluginDependencyRuleTest`, a source-scan lint over the
submodule sources: the observed set must equal the allowlist exactly, so a
new reference fails the build and a stale entry fails it too — the entry
is removed in the same change that removed the reference. Run it with
`./gradlew test --tests '*DependencyRule*'` — the milestone's phase gate
and a checklist item on every extraction pull request. The wrapper `test`
task runs in neither CI nor the maintenance-verify chain today; wiring it
in is a separate scope decision.

## Design resolutions

### The gate-verdict contract stays test-only

`Requirements.check(...)` stays package-private and the `RejectionReason`
enum — nineteen constants — remains internal review instrumentation. Any
consumer outside tests is an API widening: a real change dressed as a
refactor, which would break the zero-delta attestation every extraction
relies on. The cheapest legitimate future consumer is per-gate rejection
tallies in the debug overlay panel — its own post-milestone pull request,
not bundled into the refactor. Rendering "why can't I use this" tooltips
off the verdicts is rejected outright: the tooltip overlay walks only the
computed path, which contains all-passed transports — the rejected ones
are exactly what it never sees, so the feature needs rejected-transport
rendering and hit-testing first. Exposing verdicts over the plugin-message
API is parked inside the protocol-design work rather than decided here.

### `BankVisitState` replaces the positional `boolean` at seams

The banked/unbanked `boolean` that crosses package boundaries becomes an
enum at API surfaces — `BankVisitState.CARRIED` and
`BankVisitState.BANKED` — at the availability views
(`getTransportsPacked`, `getUsableTeleports`, `getTransportAvailability`),
`TransportEligibility.usable`, `PathStep.isBankVisited`, the visited-store
and node-store signatures, and the search-loop reads. Internals stay
primitive: `state & 1`, the `FLAG_BANK_VISITED` byte and the twin visited
arrays are storage encoding, not readability problems. There is no
generalized flags container — league, free-to-play and sailing status are
per-refresh account facts (snapshot inputs), not mid-search state
transitions; the bank-visit edge is the only confirmed second dimension.
The change lands as a standalone mechanical pull request placed before the
API/presentation and scheduler work — it crosses the engine, the
middleware, the shell and the harness twins, so it cannot nest inside a
single subsystem extraction without re-diffing the same signatures twice.

### Coupling direction is declared and ratcheted

Resolved by the dependency rule above: the rule is declared, today's
violations are frozen in the allowlist, and the lint keeps the set
non-growing. Migration ownership rides the extractions named in the table —
the `override()` reads fold into the settings service, the
`isInsidePoh`/`POH_*` references into the POH service, and the resource
anchors disappear when the loader lands self-anchored.

## Diagram

Current state as surveyed, then the target state. Node names match the
survey's cluster index. In the current block, dashed arrows are the
backward coupling the dependency rule outlaws — every dashed edge is a
site in the frozen allowlist. In the target block, filled nodes are the
services and value types the refactor introduces; grey nodes already exist
and keep their shape — only their seams change. Edge labels that cross
threads carry the producer-thread to consumer-thread pair.

```mermaid
flowchart TD
    subgraph RL["RuneLite host"]
        Client["Client - mutable game state"]
        Events["event bus"]
        ConfigMgr["ConfigManager"]
    end

    subgraph Shell["Plugin shell - two god objects"]
        Plugin["ShortestPathPlugin - lifecycle ordering, item and varbit handlers, spirit-tree scrape, POH remap and isInsidePoh, scheduler internals, refresh triggers, plugin-message API, menu verbs, transport display, widget geometry, overlay registration"]
        PFC["PathfinderConfig - cached config fields, override reads, item collection and banks, availableSpiritTrees, refresh orchestration, destinations and filtering, availability views, requirement wiring, exact account prep"]
    end

    subgraph Req["Requirement middleware - landed"]
        PSS["PlayerStateSource"]
        RC["RequirementContext"]
        RP["RoutingPolicy"]
        Gates["Requirements gates and TransportEligibility"]
    end

    subgraph Engine["Engine - already coherent"]
        PF["Pathfinder search core"]
        Exact["Exact backend"]
    end

    subgraph Data["Leaf packages - data"]
        TSV["TSV data loading"]
        Parser["Transport TSV parser"]
        Leagues["Leagues"]
    end

    subgraph Present["Leaf packages - presentation"]
        PathOv["Path rendering overlays"]
        HighOv["Highlight overlays"]
        DbgOv["Debug overlay"]
    end

    Panel["Config panel - writer"]
    Util["Leaf utilities"]

    Events --> Plugin
    Plugin --> Client
    Plugin --> ConfigMgr
    Panel --> ConfigMgr
    Plugin --> PFC
    PFC --> Gates
    PSS -->|requirement-state reads| Client
    PSS --> RC
    RC --> Gates
    RP --> Gates
    Parser --> TSV
    TSV --> PFC
    Leagues --> Gates
    PFC -->|availability views| PF
    PFC -->|account snapshot| Exact
    Plugin --> PathOv
    Plugin --> HighOv
    Plugin --> DbgOv
    Plugin --> Util
    Gates -.->|isInsidePoh x4| Plugin
    PFC -.->|override x16, POH statics, isInsidePoh| Plugin
    TSV -.->|override x3 and class anchor| Plugin
    PF -.->|class anchor, POH statics, isInsidePoh| Plugin
    Leagues -.->|class anchor| Plugin
    PathOv -.->|about 90 plugin.* reads, isInsidePoh x6| Plugin
    DbgOv -.->|getDebugState| Plugin

    classDef shell fill:#f8e3e3,stroke:#a33,color:#000
    class Plugin,PFC shell
```

In target shape every cluster above has a lane: producers watch game state
on the client thread and publish immutable facts; the coordinator hears
"what changed" and is the scheduler's only caller; the engine consumes
snapshots on the worker thread; presentation reads published handles on
the render thread.

```mermaid
flowchart TD
    subgraph ShellT["Plugin shell residue"]
        ShellRes["lifecycle ordering, one-line event forwarders, overlay and keybind registration"]
    end

    subgraph Prod["Client-thread producers"]
        Cfg["Config access and overrides - settings service"]
        PSS["PlayerStateSource - sole Client reader"]
        Items["Player item state"]
        Trees["Spirit trees"]
        POH["POH service"]
        Geo["Widget and UI geometry"]
    end

    subgraph Coord["Coordination - notification flows inward only"]
        RCO["RefreshCoordinator"]
        Sched["PathScheduler"]
        Diag["Diagnostics"]
    end

    subgraph EngineT["Engine - immutable per-refresh inputs"]
        RC["RequirementContext - immutable snapshot"]
        RP["RoutingPolicy - settings view"]
        Gates["Requirements gates and TransportEligibility"]
        Skills["Player skill levels"]
        TSV["TSV data loading"]
        Leagues["Leagues"]
        PF["Pathfinder search core"]
        Exact["Exact backend"]
    end

    subgraph PresentT["Presentation"]
        API["Plugin-message API"]
        Menu["Menu and target-setting verbs"]
        TPres["Transport presentation"]
        Panel["Config panel - writer"]
        PathOv["Path rendering overlays"]
        HighOv["Highlight overlays"]
        DbgOv["Debug overlay"]
    end

    Util["Leaf utilities"]

    ShellRes -->|one-line event forwarders| Prod
    ShellRes -->|one-line event forwarders| RCO
    PSS -->|captures snapshot - client to worker| RC
    Items -->|item pools| RC
    Trees -->|tree set| RC
    POH -->|region facts| RC
    Geo -->|widget reads| Trees
    Cfg -->|projects| RP
    Cfg -->|config changed| RCO
    Items -->|items changed| RCO
    Trees -->|tree set changed| RCO
    POH -->|POH facts changed| RCO
    RCO -->|sole caller - client to worker| Sched
    Sched -->|submit search| PF
    Sched -->|submit search| Exact
    Sched -->|records outcomes| Diag
    RC --> Gates
    RP --> Gates
    Skills --> Gates
    TSV -->|destinations and transports| Gates
    Leagues --> Gates
    Gates -->|availability views| PF
    Gates -->|availability views| Exact
    Menu -->|verbs| Sched
    API -->|queries and restarts| Sched
    API -->|config payload| Cfg
    Panel -->|read write listen| Cfg
    Panel -->|owned items| Items
    TPres -->|display strings| API
    TPres -->|display strings| PathOv
    Sched -->|publishes ActiveSearch - worker to render| PathOv
    PathOv -->|settings view| Cfg
    PathOv -->|geometry| Geo
    PathOv -->|POH facts| POH
    HighOv -->|pickup facts| Items
    DbgOv -->|reads snapshot| Diag

    classDef extracted fill:#dceeff,stroke:#2a62b0,color:#000
    classDef existing fill:#f2f2f2,stroke:#777,color:#000
    class Cfg,Items,POH,Geo,RCO,Sched,Diag,API,Menu,TPres,Skills extracted
    class PSS,RC,RP,Gates,TSV,Leagues,PF,Exact,PathOv,HighOv,DbgOv,Panel,Util,ShellRes,Trees existing
```

A combined both-states overview is deliberately omitted: compressing both
graphs into one stays legible only under roughly twenty nodes, and the
honest count is well above it.
