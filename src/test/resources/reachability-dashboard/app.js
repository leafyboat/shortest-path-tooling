// ── Configuration constants ───────────────────────────────────────────
const tileBaseUrl = "https://maps.runescape.wiki/osrs/versions/2026-08-12_a/tiles/rendered";
const MAP_ID = -1;
const MIN_ZOOM = -4;
const MIN_NATIVE_ZOOM = -2;
const MAX_ZOOM = 4;
const MAX_NATIVE_ZOOM = 3;

const TRANSPORT_PALETTE = {
  entry:    { INVENTORY_VALID: "#2d6a4f", BANK_VALID: "#93c5a2", INVALID: "#6b7280" },
  exit:     { INVENTORY_VALID: "#d1495b", BANK_VALID: "#f2a6af", INVALID: "#6b7280" },
  teleport: { INVENTORY_VALID: "#7c3aed", BANK_VALID: "#c4b5fd", INVALID: "#6b7280" }
};

const MARKER_COLORS = {
  start: "#1d4ed8",
  target: "#2d6a4f",
  closest: "#d97706",
  bank: "#f59e0b"
};
const MARKER_COLOR_DEFAULT = "#475569";

const HIGHLIGHT_STYLE = {
  radius: 8,
  color: "#f59e0b",
  fillColor: "#fde68a",
  fillOpacity: 0.9,
  weight: 3
};

const HOVERED_TILE_STYLE = {
  color: "#fde047",
  weight: 1.5,
  fillColor: "#fde047",
  fillOpacity: 0.1,
  interactive: false
};

// ── DOM references ────────────────────────────────────────────────────
const summaryEl = document.getElementById("summary");
const runListEl = document.getElementById("run-list");
const runInfoDetailsEl = document.getElementById("run-info-details");
const runInfoPanel = document.getElementById("run-info-panel");
const runInfoToggle = document.getElementById("run-info-toggle");
const runInfoOverviewEl = document.getElementById("run-info-overview");
const runInfoPathEl = document.getElementById("run-info-path");
const runInfoBankEl = document.getElementById("run-info-bank");
const runInfoTabButtons = Array.from(document.querySelectorAll(".run-info-tab"));
const runInfoTabPanes = Array.from(document.querySelectorAll(".run-info-tab-pane"));
const bankTabButton = runInfoTabButtons.find(btn => btn.dataset.tab === "bank");
const runSearchEl = document.getElementById("run-search");
const unreachedOnlyEl = document.getElementById("unreached-only");
const centerTargetEl = document.getElementById("center-target");
const prevRunEl = document.getElementById("prev-run");
const nextRunEl = document.getElementById("next-run");
const mapCoordinatesEl = document.getElementById("map-coordinates");
const transportInfoEl = document.getElementById("transport-info");
const bundleSelectEl = document.getElementById("bundle-select");
const sidebarEl = document.getElementById("sidebar");
const sidebarToggle = document.getElementById("sidebar-toggle");

// ── Mutable state ─────────────────────────────────────────────────────
/** Base URL for the currently loaded bundle (empty string for root report.json) */
let currentBundleBase = "";
window.currentBundleBase = currentBundleBase;

let currentPlane = 0;
let selectedRun = null;
let allRuns = [];
let loadedBundles = [];
let reportTransportLayers = [];
let selectedTransportOverlay = null;
let hoveredTileOverlay = null;
let markerLayers = [];
let transportOverlayLayers = [];

// ── Map setup ─────────────────────────────────────────────────────────
const WikiTileLayer = L.TileLayer.extend({
  getTileUrl(coords) {
    return `${tileBaseUrl}/${MAP_ID}/${coords.z}/${currentPlane}_${coords.x}_${-(1 + coords.y)}.png`;
  },

  createTile(coords, done) {
    const tile = L.TileLayer.prototype.createTile.call(this, coords, done);
    tile.onerror = () => {};
    return tile;
  }
});

const map = L.map("map", {
  crs: L.CRS.Simple,
  minZoom: MIN_ZOOM,
  maxZoom: MAX_ZOOM,
  zoomSnap: 1,
  center: [3200, 3200],
  zoom: 1,
  maxBounds: [[-1000, -1000], [13800, 13800]],
  maxBoundsViscosity: 0.5
});
window._dashboardMap = map;

// Chunk grid pane sits below the default overlayPane (z:400) so that
// league region borders always render on top of chunk borders.
map.createPane('chunkGridPane');
map.getPane('chunkGridPane').style.zIndex = 350;

map.attributionControl.addAttribution('&copy; <a href="https://runescape.wiki/">RuneScape Wiki</a>');

const baseLayer = new WikiTileLayer("", {
  minZoom: MIN_ZOOM,
  minNativeZoom: MIN_NATIVE_ZOOM,
  maxNativeZoom: MAX_NATIVE_ZOOM,
  maxZoom: MAX_ZOOM,
  noWrap: true
}).addTo(map);
const transportRenderer = L.canvas({ padding: 0.2 });

const transportLayerState = {
  entry: false,
  exit: false,
  teleport: false
};

// Extension layer toggles registered by plugins (e.g. profiler heatmap)
const extensionLayerToggles = [];

const TransportLayerControl = L.Control.extend({
  options: {
    position: "topright"
  },

  onAdd() {
    const container = L.DomUtil.create("div", "leaflet-bar leaflet-control transport-layer-control");
    this._fieldset = L.DomUtil.create("fieldset", "", container);
    const legend = L.DomUtil.create("legend", "", this._fieldset);
    legend.textContent = "Transport Layers";

    [
      { key: "entry", label: "Entries" },
      { key: "exit", label: "Exits" },
      { key: "teleport", label: "Teleports" }
    ].forEach(item => {
      const label = L.DomUtil.create("label", "", this._fieldset);
      const input = L.DomUtil.create("input", "", label);
      input.type = "checkbox";
      input.checked = transportLayerState[item.key];
      const text = L.DomUtil.create("span", "", label);
      text.textContent = item.label;
      L.DomEvent.disableClickPropagation(label);
      L.DomEvent.on(input, "change", () => {
        transportLayerState[item.key] = input.checked;
        renderTransportOverlays();
      });
    });

    // Add any extension toggles registered before control was created
    extensionLayerToggles.forEach(toggle => this._addToggle(toggle));

    L.DomEvent.disableClickPropagation(container);
    L.DomEvent.disableScrollPropagation(container);
    return container;
  },

  _addToggle(toggle) {
    const label = L.DomUtil.create("label", "", this._fieldset);
    const input = L.DomUtil.create("input", "", label);
    input.type = "checkbox";
    input.checked = toggle.checked || false;
    const text = L.DomUtil.create("span", "", label);
    text.textContent = toggle.label;
    L.DomEvent.disableClickPropagation(label);
    L.DomEvent.on(input, "change", () => {
      toggle.onChange(input.checked);
    });
    toggle._input = input;
  },

  addToggle(toggle) {
    if (this._fieldset) {
      this._addToggle(toggle);
    }
  }
});

const transportLayerControl = new TransportLayerControl();
map.addControl(transportLayerControl);

// ── Path colour legend (mounted only when a run actually has a bank leg) ─
const PathLegendControl = L.Control.extend({
  options: { position: "bottomleft" },
  onAdd() {
    const div = L.DomUtil.create("div", "heatmap-legend leaflet-control path-legend-card");
    const title = L.DomUtil.create("div", "heatmap-legend-title", div);
    title.textContent = "Path";
    [
      { color: pathSegmentColor(true, false), label: "Before bank" },
      { color: pathSegmentColor(true, true),  label: "After bank" }
    ].forEach(({ color, label }) => {
      const row = L.DomUtil.create("div", "path-legend-entry", div);
      const swatch = L.DomUtil.create("span", "path-legend-swatch", row);
      swatch.style.background = color;
      const text = L.DomUtil.create("span", "", row);
      text.textContent = label;
    });
    L.DomEvent.disableClickPropagation(div);
    return div;
  }
});
const pathLegendControl = new PathLegendControl();
let pathLegendMounted = false;
function setPathLegendVisible(visible) {
  if (visible && !pathLegendMounted) {
    pathLegendControl.addTo(map);
    pathLegendMounted = true;
  } else if (!visible && pathLegendMounted) {
    map.removeControl(pathLegendControl);
    pathLegendMounted = false;
  }
}

// ── League Region colour legend ───────────────────────────────────────
const LeagueRegionLegendControl = L.Control.extend({
  options: { position: "bottomleft" },
  onAdd() {
    const div = L.DomUtil.create("div", "heatmap-legend leaflet-control league-region-legend-card");
    const title = L.DomUtil.create("div", "heatmap-legend-title", div);
    title.textContent = "League Regions";
    Object.entries(LEAGUE_REGION_COLORS)
      .filter(([name]) => name !== "NEUTRAL")
      .forEach(([name, color]) => {
        const row = L.DomUtil.create("div", "path-legend-entry", div);
        const swatch = L.DomUtil.create("span", "path-legend-swatch", row);
        swatch.style.background = color;
        swatch.style.height = "12px";
        swatch.style.width = "12px";
        swatch.style.borderRadius = "2px";
        const text = L.DomUtil.create("span", "", row);
        text.textContent = name === "MISTHALIN"
          ? "Misthalin (Blocked)"
          : name.charAt(0).toUpperCase() + name.slice(1).toLowerCase();
      });
    L.DomEvent.disableClickPropagation(div);
    return div;
  }
});
const leagueRegionLegendControl = new LeagueRegionLegendControl();
let leagueRegionLegendMounted = false;
function setLeagueRegionLegendVisible(visible) {
  if (visible && !leagueRegionLegendMounted) {
    leagueRegionLegendControl.addTo(map);
    leagueRegionLegendMounted = true;
  } else if (!visible && leagueRegionLegendMounted) {
    map.removeControl(leagueRegionLegendControl);
    leagueRegionLegendMounted = false;
  }
}

// Public API for extensions to add map layer toggles
window.addMapLayerToggle = function(toggle) {
  extensionLayerToggles.push(toggle);
  transportLayerControl.addToggle(toggle);
};

// ── Demonic Pacts League — region overlay ─────────────────────────────
//
// Rendered only when the loaded bundle has {@code report.seasonal === true}.
// regions.tsv is published next to index.html by the asset writer, so we
// fetch it once and cache the parsed layer group.
const LEAGUE_REGION_COLORS = {
  MISTHALIN:  "#dc2626",
  VARLAMORE:  "#22c55e",
  KARAMJA:    "#f59e0b",
  ASGARNIA:   "#3b82f6",
  KANDARIN:   "#a855f7",
  FREMENNIK:  "#06b6d4",
  KOUREND:    "#6366f1",
  WILDERNESS: "#f43f5e",
  MORYTANIA:  "#737373",
  DESERT:     "#eab308",
  TIRANNWN:   "#10b981",
  NEUTRAL:    "#9ca3af"
};

const leagueRegionsLayer = L.layerGroup();
let leagueRegionsToggle = null;
let leagueRegionsToggleVisible = false;
let leagueRegionsFetchPromise = null;
let leagueRegionsLoaded = false;

async function fetchLeagueRegions() {
  if (leagueRegionsFetchPromise) {
    return leagueRegionsFetchPromise;
  }
  leagueRegionsFetchPromise = (async () => {
    const resp = await fetch("regions.tsv");
    if (!resp.ok) {
      throw new Error("regions.tsv missing — run the seasonalDashboard task to publish it.");
    }
    const text = await resp.text();
    text.split(/\r?\n/).forEach(line => {
      if (!line || line.startsWith("#")) return;
      const [idStr, regionName] = line.split("\t");
      const regionId = parseInt(idStr, 10);
      if (!Number.isFinite(regionId) || !regionName) return;
      const region = regionName.trim();
      const color = LEAGUE_REGION_COLORS[region] || LEAGUE_REGION_COLORS.NEUTRAL;
      const isBlocked = region === "MISTHALIN";
      // Region id encodes (chunkX << 8) | chunkY, each chunk is 64 tiles.
      const minX = (regionId >> 8) * 64;
      const minY = (regionId & 0xff) * 64;
      const rect = L.rectangle([
        [minY, minX],
        [minY + 64, minX + 64]
      ], {
        color,
        weight: isBlocked ? 1.5 : 0.5,
        opacity: isBlocked ? 0.7 : 0.4,
        fillColor: color,
        fillOpacity: isBlocked ? 0.35 : 0.18,
        interactive: false
      });
      leagueRegionsLayer.addLayer(rect);
    });
    leagueRegionsLoaded = true;
  })();
  return leagueRegionsFetchPromise;
}

function ensureLeagueRegionsToggle() {
  if (leagueRegionsToggle) return;
  leagueRegionsToggle = {
    label: "League regions",
    checked: true,
    onChange: (visible) => {
      if (visible && leagueRegionsLoaded && leagueRegionsToggleVisible) {
        leagueRegionsLayer.addTo(map);
      } else {
        map.removeLayer(leagueRegionsLayer);
      }
      setLeagueRegionLegendVisible(visible && leagueRegionsToggleVisible);
    }
  };
  window.addMapLayerToggle(leagueRegionsToggle);
}

function setLeagueRegionsToggleVisible(visible) {
  leagueRegionsToggleVisible = visible;
  if (!leagueRegionsToggle || !leagueRegionsToggle._input) return;
  // The toggle row is the parent <label> of the checkbox input.
  const row = leagueRegionsToggle._input.parentElement;
  if (row) row.style.display = visible ? "" : "none";
}

async function applyLeagueRegionsOverlay(seasonal) {
  if (!seasonal) {
    map.removeLayer(leagueRegionsLayer);
    setLeagueRegionsToggleVisible(false);
    setLeagueRegionLegendVisible(false);
    return;
  }
  ensureLeagueRegionsToggle();
  setLeagueRegionsToggleVisible(true);
  try {
    await fetchLeagueRegions();
  } catch (err) {
    console.warn("[league-regions]", err);
    return;
  }
  const checked = leagueRegionsToggle._input && leagueRegionsToggle._input.checked;
  if (checked) {
    leagueRegionsLayer.addTo(map);
  }
  setLeagueRegionLegendVisible(Boolean(checked));
}

// ── F2P — members-chunk overlay ───────────────────────────────────────
//
// Mirrors the league-region overlay. Rendered only when the loaded bundle
// has {@code report.f2p === true}. f2p_regions.tsv is published next to
// index.html by the asset writer (from the plugin's /f2p/regions.tsv
// resource) — one row per region: "<regionId>\t<F2P|MEMBERS>".
const F2P_MEMBERS_COLOR = "#dc2626";

const f2pRegionsLayer = L.layerGroup();
let f2pRegionsToggle = null;
let f2pRegionsToggleVisible = false;
let f2pRegionsFetchPromise = null;
let f2pRegionsLoaded = false;

async function fetchF2pRegions() {
  if (f2pRegionsFetchPromise) {
    return f2pRegionsFetchPromise;
  }
  f2pRegionsFetchPromise = (async () => {
    const resp = await fetch("f2p_regions.tsv");
    if (!resp.ok) {
      throw new Error("f2p_regions.tsv missing — run with -PdashboardF2p=true and ensure /f2p/regions.tsv is on the plugin classpath.");
    }
    const text = await resp.text();
    text.split(/\r?\n/).forEach(line => {
      if (!line || line.startsWith("#")) return;
      const [idStr, tag] = line.split("\t");
      const regionId = parseInt(idStr, 10);
      if (!Number.isFinite(regionId) || !tag) return;
      if (tag.trim().toUpperCase() !== "MEMBERS") return;
      // Region id encodes (chunkX << 8) | chunkY, each chunk is 64 tiles.
      const minX = (regionId >> 8) * 64;
      const minY = (regionId & 0xff) * 64;
      const rect = L.rectangle([
        [minY, minX],
        [minY + 64, minX + 64]
      ], {
        color: F2P_MEMBERS_COLOR,
        weight: 0.5,
        opacity: 0.5,
        fillColor: F2P_MEMBERS_COLOR,
        fillOpacity: 0.25,
        interactive: false
      });
      f2pRegionsLayer.addLayer(rect);
    });
    f2pRegionsLoaded = true;
  })();
  return f2pRegionsFetchPromise;
}

function ensureF2pRegionsToggle() {
  if (f2pRegionsToggle) return;
  f2pRegionsToggle = {
    label: "Members-only chunks",
    checked: true,
    onChange: (visible) => {
      if (visible && f2pRegionsLoaded && f2pRegionsToggleVisible) {
        f2pRegionsLayer.addTo(map);
      } else {
        map.removeLayer(f2pRegionsLayer);
      }
    }
  };
  window.addMapLayerToggle(f2pRegionsToggle);
}

function setF2pRegionsToggleVisible(visible) {
  f2pRegionsToggleVisible = visible;
  if (!f2pRegionsToggle || !f2pRegionsToggle._input) return;
  const row = f2pRegionsToggle._input.parentElement;
  if (row) row.style.display = visible ? "" : "none";
}

async function applyF2pRegionsOverlay(f2p) {
  if (!f2p) {
    map.removeLayer(f2pRegionsLayer);
    setF2pRegionsToggleVisible(false);
    return;
  }
  ensureF2pRegionsToggle();
  setF2pRegionsToggleVisible(true);
  try {
    await fetchF2pRegions();
  } catch (err) {
    console.warn("[f2p-regions]", err);
    return;
  }
  const checked = f2pRegionsToggle._input && f2pRegionsToggle._input.checked;
  if (checked) {
    f2pRegionsLayer.addTo(map);
  }
}

// ── Chunk coordinate labels overlay ──────────────────────────────────
//
// Shows "cx, cy" at the centre of every 64×64 map region.
// cx = regionId >> 8, cy = regionId & 0xFF (same encoding as regions.tsv).
// Labels are rebuilt from the current viewport on every moveend so the
// layer stays light even at high zoom; hidden below zoom 2 where each
// region would be too small to read.

const chunkCoordsLayer = L.layerGroup();
const chunkGridLayer = L.layerGroup();
let chunkCoordsMounted = false;

function buildChunkCoordsLabels() {
  chunkCoordsLayer.clearLayers();
  chunkGridLayer.clearLayers();
  if (map.getZoom() < 2) return;
  const bounds = map.getBounds();
  const minCx = Math.max(0,   Math.floor(bounds.getWest()  / 64));
  const maxCx = Math.min(255, Math.floor(bounds.getEast()  / 64));
  const minCy = Math.max(0,   Math.floor(bounds.getSouth() / 64));
  const maxCy = Math.min(255, Math.floor(bounds.getNorth() / 64));

  // Chunk border grid lines — styled for current theme
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  const gridColor = isDark ? 'rgba(255,255,255,0.18)' : 'rgba(0,0,0,0.22)';
  const polyOpts = { color: gridColor, weight: 1, opacity: 1, interactive: false, pane: 'chunkGridPane' };
  for (let cx = minCx; cx <= maxCx + 1; cx++) {
    L.polyline([[minCy * 64, cx * 64], [(maxCy + 1) * 64, cx * 64]], polyOpts).addTo(chunkGridLayer);
  }
  for (let cy = minCy; cy <= maxCy + 1; cy++) {
    L.polyline([[cy * 64, minCx * 64], [cy * 64, (maxCx + 1) * 64]], polyOpts).addTo(chunkGridLayer);
  }

  for (let cx = minCx; cx <= maxCx; cx++) {
    for (let cy = minCy; cy <= maxCy; cy++) {
      L.marker([cy * 64 + 32, cx * 64 + 32], {
        icon: L.divIcon({
          className: "",
          html: `<div class="chunk-coord-label">x:${cx}, y:${cy}</div>`,
          iconSize: [0, 0],
          iconAnchor: [0, 0]
        }),
        interactive: false,
        zIndexOffset: -1000
      }).addTo(chunkCoordsLayer);
    }
  }
}

map.on("moveend", () => { if (chunkCoordsMounted) buildChunkCoordsLabels(); });

window.addMapLayerToggle({
  label: "Chunk coords",
  checked: false,
  onChange(visible) {
    if (visible) {
      buildChunkCoordsLabels();
      chunkGridLayer.addTo(map);
      chunkCoordsLayer.addTo(map);
      chunkCoordsMounted = true;
    } else {
      map.removeLayer(chunkGridLayer);
      map.removeLayer(chunkCoordsLayer);
      chunkGridLayer.clearLayers();
      chunkCoordsLayer.clearLayers();
      chunkCoordsMounted = false;
    }
  }
});

// ── Coordinate utilities ──────────────────────────────────────────────

function worldToLatLng(point) {
  return [point.y + 0.5, point.x + 0.5];
}

function latLngToWorldPoint(latlng) {
  return {
    x: Math.floor(latlng.lng),
    y: Math.floor(latlng.lat),
    plane: currentPlane
  };
}

function formatCoordinateText(point) {
  return `${point.x}, ${point.y}, ${point.plane}`;
}

function updateMapCoordinates(point, copied = false) {
  mapCoordinatesEl.textContent = copied
    ? `Copied: ${formatCoordinateText(point)}`
    : formatCoordinateText(point);
}

function renderHoveredTile(point) {
  if (hoveredTileOverlay) {
    map.removeLayer(hoveredTileOverlay);
  }

  hoveredTileOverlay = L.rectangle([
    [point.y, point.x],
    [point.y + 1, point.x + 1]
  ], HOVERED_TILE_STYLE).addTo(map);
}

function clearLayers() {
  markerLayers.forEach(layer => map.removeLayer(layer));
  transportOverlayLayers.forEach(layer => map.removeLayer(layer));
  if (selectedTransportOverlay) {
    map.removeLayer(selectedTransportOverlay);
    selectedTransportOverlay = null;
  }
  if (hoveredTileOverlay) {
    map.removeLayer(hoveredTileOverlay);
    hoveredTileOverlay = null;
  }
  markerLayers = [];
  transportOverlayLayers = [];
  transportInfoEl.hidden = true;
}

function addMarker(point, label, color, radius = 6) {
  const marker = L.circleMarker(worldToLatLng(point), {
    radius,
    color,
    fillColor: color,
    fillOpacity: 0.9
  }).bindTooltip(label);
  marker.addTo(map);
  markerLayers.push(marker);
}

function transportLayerColor(kind, validity) {
  return (TRANSPORT_PALETTE[kind] && TRANSPORT_PALETTE[kind][validity]) || "#6b7280";
}

function transportLayerStyle(validity) {
  if (validity === "INVENTORY_VALID") {
    return { radius: 5, fillOpacity: 0.85, weight: 2 };
  }
  if (validity === "BANK_VALID") {
    return { radius: 5, fillOpacity: 0.55, weight: 2 };
  }
  return { radius: 4, fillOpacity: 0.25, weight: 1 };
}

function isTeleportLayer(layer) {
  return layer.origin == null;
}

function transportLayerLabel(layer, kind) {
  const label = layer.displayInfo || layer.objectInfo || layer.type;
  const suffix = kind === "teleport" ? "teleport" : kind;
  return `${label} ${suffix} [${layer.validity}]`;
}

function showTransportInfo(layer, clickedKind) {
  const label = layer.displayInfo || layer.objectInfo || layer.type;
  const lines = [
    label,
    `Type: ${layer.type}`,
    `Validity: ${layer.validity}`,
    `Clicked: ${clickedKind}`
  ];
  if (layer.origin) {
    lines.push(`Entry: ${formatPoint(layer.origin)}`);
  }
  lines.push(`Exit: ${formatPoint(layer.destination)}`);
  transportInfoEl.textContent = lines.join("\n");
  transportInfoEl.hidden = false;
}

function highlightTransport(layer, clickedKind) {
  if (selectedTransportOverlay) {
    map.removeLayer(selectedTransportOverlay);
  }

  const overlayLayers = [];
  if (layer.origin) {
    overlayLayers.push(L.circleMarker(worldToLatLng(layer.origin), {
      renderer: transportRenderer,
      ...HIGHLIGHT_STYLE
    }));
  }
  overlayLayers.push(L.circleMarker(worldToLatLng(layer.destination), {
    renderer: transportRenderer,
    ...HIGHLIGHT_STYLE
  }));
  if (layer.origin) {
    overlayLayers.push(L.polyline([worldToLatLng(layer.origin), worldToLatLng(layer.destination)], {
      color: "#f59e0b",
      weight: 2,
      dashArray: "6 4",
      interactive: false
    }));
  }

  selectedTransportOverlay = L.layerGroup(overlayLayers).addTo(map);
  showTransportInfo(layer, clickedKind);

  if (layer.origin) {
    const target = clickedKind === "entry" ? layer.destination : layer.origin;
    map.flyTo(worldToLatLng(target), Math.max(map.getZoom(), 1), { duration: 0.4 });
  } else {
    map.flyTo(worldToLatLng(layer.destination), Math.max(map.getZoom(), 1), { duration: 0.4 });
  }
}

function addTransportOverlay(point, label, kind, validity, layer) {
  const color = transportLayerColor(kind, validity);
  const style = transportLayerStyle(validity);
  const marker = L.circleMarker(worldToLatLng(point), {
    renderer: transportRenderer,
    radius: style.radius,
    color,
    fillColor: color,
    fillOpacity: style.fillOpacity,
    weight: style.weight
  }).bindTooltip(label);
  marker.on("click", () => highlightTransport(layer, kind));
  marker.addTo(map);
  transportOverlayLayers.push(marker);
}

function renderTransportOverlays() {
  transportOverlayLayers.forEach(layer => map.removeLayer(layer));
  transportOverlayLayers = [];

  if (!reportTransportLayers || reportTransportLayers.length === 0) {
    return;
  }

  reportTransportLayers.forEach(layer => {
    const teleport = isTeleportLayer(layer);
    if (!teleport && transportLayerState.entry && layer.origin && layer.origin.plane === currentPlane) {
      addTransportOverlay(layer.origin, transportLayerLabel(layer, "entry"), "entry", layer.validity, layer);
    }

    if (layer.destination.plane !== currentPlane) {
      return;
    }

    if (teleport) {
      if (transportLayerState.teleport) {
        addTransportOverlay(layer.destination, transportLayerLabel(layer, "teleport"), "teleport", layer.validity, layer);
      }
    } else if (transportLayerState.exit) {
      addTransportOverlay(layer.destination, transportLayerLabel(layer, "exit"), "exit", layer.validity, layer);
    }
  });
}

function statusLabel(run) {
  const route = run.reached ? "reached" : "unreached";
  if (run.assertionPassed === true) return `pass, ${route}`;
  if (run.assertionPassed === false) return `fail, ${route}`;
  return route;
}

function formatPoint(point) {
  return `(${point.x}, ${point.y}, ${point.plane})`;
}

function markerColor(kind) {
  return MARKER_COLORS[kind] || MARKER_COLOR_DEFAULT;
}

function pathSegmentColor(reached, bankVisited) {
  if (bankVisited) {
    return reached ? "#7c3aed" : "#b45309";
  }
  return reached ? "#2d6a4f" : "#d1495b";
}

function buildPathSegments(path) {
  if (!path || path.length < 2) {
    return [];
  }

  const segments = [];
  let currentSegment = [path[0], path[1]];
  let currentBankVisited = Boolean(path[1].bankVisited);

  for (let i = 2; i < path.length; i++) {
    const point = path[i];
    const edgeBankVisited = Boolean(point.bankVisited);
    if (edgeBankVisited !== currentBankVisited) {
      segments.push({ points: currentSegment, bankVisited: currentBankVisited });
      currentSegment = [path[i - 1], point];
      currentBankVisited = edgeBankVisited;
    } else {
      currentSegment.push(point);
    }
  }

  segments.push({ points: currentSegment, bankVisited: currentBankVisited });
  return segments;
}

function buildDetails(run) {
  const lines = [];

  if (run.routeModeId) {
    lines.push("Route mode", `  id: ${run.routeModeId}`);
    if (run.teleportationItems) {
      lines.push(`  teleportationItems: ${run.teleportationItems}`);
    }
    if (run.includeBankPath != null) {
      lines.push(`  includeBankPath: ${run.includeBankPath}`);
    }
    if (run.lumbridgeDiaryEliteStub != null) {
      lines.push(`  lumbridgeDiaryElite (stub): ${run.lumbridgeDiaryEliteStub}`);
    }
  }
  if (run.bankVisitedOnPath != null) {
    lines.push(`Bank visited on path: ${run.bankVisitedOnPath}`);
  }
  if (run.bankEvents && run.bankEvents.length > 0) {
    lines.push("Bank events");
    run.bankEvents.forEach(ev => {
      lines.push(`  - ${ev.bankName || "Bank"} @ step ${ev.stepIndex} ${formatPoint(ev.location)}`);
    });
  }
  if (lines.length > 0) lines.push("");

  lines.push(
    `Name: ${run.name}`,
    `Category: ${run.category || "default"}`,
    `Assertion: ${run.assertionPassed === true ? "passed" : run.assertionPassed === false ? "failed" : "n/a"}`
  );
  if (run.assertionMessage) {
    lines.push(`Assertion message: ${run.assertionMessage}`);
  }
  lines.push(
    `Reached: ${run.reached}`,
    `Termination: ${run.terminationReason}`,
    `Path length: ${run.path.length}`,
    `Nodes checked: ${run.stats.nodesChecked}`,
    `Transports checked: ${run.stats.transportsChecked}`,
    `Elapsed: ${formatElapsed(run.stats.elapsedNanos)}`,
    `Start: ${formatPoint(run.start)}`,
    `Target: ${formatPoint(run.target)}`,
    `Closest reached: ${formatPoint(run.closestReachedPoint)}`
  );

  if (run.details && run.details.length > 0) {
    lines.push("", "Scenario details:");
    run.details.forEach(detail => lines.push(`- ${detail}`));
  }

  if (run.markers && run.markers.length > 0) {
    lines.push("", "Markers:");
    run.markers.forEach(marker => lines.push(`- ${marker.label}: ${formatPoint(marker.point)}`));
  }

  if (run.transports && run.transports.length > 0) {
    lines.push("", "Transports used:");
    run.transports.forEach(step => {
      const label = step.displayInfo || step.objectInfo || step.type;
      lines.push(`- step ${step.stepIndex}: ${step.type} -> ${label}`);
    });
  }

  if (run.collisionWindow) {
    const { width, height, originX, originY, plane } = run.collisionWindow;
    lines.push("", `Collision window: ${width}x${height} @ (${originX}, ${originY}, ${plane})`);
  }

  return lines.join("\n");
}

// ── Structured overview / path tab rendering ──────────────────────────

function clearEl(el) {
  while (el.firstChild) {
    el.removeChild(el.firstChild);
  }
}

function appendKV(dl, key, value) {
  if (value === undefined || value === null || value === "") {
    return;
  }
  const dt = document.createElement("dt");
  dt.textContent = key;
  const dd = document.createElement("dd");
  dd.textContent = String(value);
  dl.appendChild(dt);
  dl.appendChild(dd);
}

function makeSectionTitle(text) {
  const el = document.createElement("div");
  el.className = "run-info-section-title";
  el.textContent = text;
  return el;
}

function makeChip(kind, labelText, subText, onClick) {
  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = `run-info-chip run-info-chip--${kind}`;
  const dot = document.createElement("span");
  dot.className = "chip-dot";
  chip.appendChild(dot);
  const label = document.createElement("span");
  label.className = "run-info-chip-label";
  label.textContent = labelText;
  chip.appendChild(label);
  if (subText) {
    const step = document.createElement("span");
    step.className = "run-info-chip-step";
    step.textContent = subText;
    chip.appendChild(step);
  }
  chip.title = subText ? `${labelText} · ${subText}` : labelText;
  chip.addEventListener("click", e => {
    e.stopPropagation();
    onClick();
  });
  return chip;
}

function bankChipsFor(run) {
  const chips = document.createElement("div");
  chips.className = "run-info-chips";
  (run.bankEvents || []).forEach(ev => {
    const label = ev.bankName || "Bank";
    const chip = makeChip("bank", label, `step ${ev.stepIndex}`, () => flyAndFlash(ev.location));
    if (ev.pickups && ev.pickups.length) {
      chip.title = `Picked up from bank: ${ev.pickups.map(p => p.label).join(", ")}`;
    } else if (ev.transportsAfterBank && ev.transportsAfterBank.length) {
      chip.title = `Transports after bank: ${ev.transportsAfterBank.join(" → ")}`;
    }
    chips.appendChild(chip);
  });
  return chips;
}

function renderBankChipSection(container, run, sectionTitle) {
  const events = run.bankEvents || [];
  container.appendChild(makeSectionTitle(`${sectionTitle} (${events.length})`));
  container.appendChild(bankChipsFor(run));
}

// Renders a single pickup as: "- 2× Water rune" with an optional nested equivalents sub-list
// ("also accepts: Mist rune, Mud rune, Staff of water, …"). Equivalents are compact and muted;
// missing staves fall through to their reflected ItemID constant name so auditing is straightforward.
function appendPickupItem(listEl, pickup) {
  const li = document.createElement("li");
  const label = document.createElement("span");
  label.className = "pickup-label";
  label.textContent = pickup.label;
  li.appendChild(label);
  if (pickup.equivalents && pickup.equivalents.length) {
    const equiv = document.createElement("div");
    equiv.className = "pickup-equivalents";
    const note = document.createElement("span");
    note.className = "pickup-equivalents-note";
    note.textContent = "also accepts: ";
    equiv.appendChild(note);
    const value = document.createElement("span");
    value.className = "pickup-equivalents-list";
    value.textContent = pickup.equivalents.join(", ");
    equiv.appendChild(value);
    li.appendChild(equiv);
  }
  listEl.appendChild(li);
}

function renderPickupsFor(container, events) {
  const withPickups = events.filter(ev => ev.pickups && ev.pickups.length);
  if (withPickups.length === 0) return;

  const pickupsSection = document.createElement("div");
  pickupsSection.className = "run-info-pickups";
  const singleEvent = events.length === 1;
  withPickups.forEach(ev => {
    const row = document.createElement("div");
    row.className = "run-info-pickups-row";
    const label = document.createElement("div");
    label.className = "run-info-pickups-label";
    label.textContent = singleEvent
      ? "Picked up from bank:"
      : `From ${ev.bankName || `step ${ev.stepIndex}`}:`;
    const list = document.createElement("ul");
    list.className = "run-info-pickups-list";
    ev.pickups.forEach(p => appendPickupItem(list, p));
    row.appendChild(label);
    row.appendChild(list);
    pickupsSection.appendChild(row);
  });
  container.appendChild(pickupsSection);
}

function flyAndFlash(point) {
  currentPlane = point.plane;
  baseLayer.redraw();
  map.fire("planechange");
  map.flyTo(worldToLatLng(point), Math.max(map.getZoom(), 2), { duration: 0.4 });
  renderHoveredTile(point);
}

function formatElapsed(nanos) {
  if (nanos == null) return "n/a";
  return `${(nanos / 1_000_000).toFixed(2)} ms`;
}

function buildOverviewPane(run) {
  clearEl(runInfoOverviewEl);

  const name = document.createElement("div");
  name.className = "run-info-name";
  const statusDot = document.createElement("span");
  statusDot.className = "run-info-status " +
    (run.reached ? "run-info-status--reached" : "run-info-status--unreached");
  name.appendChild(statusDot);
  name.appendChild(document.createTextNode(run.name));
  runInfoOverviewEl.appendChild(name);

  const kv = document.createElement("dl");
  kv.className = "run-info-kv";
  if (run.routeModeId) {
    appendKV(kv, "Mode", run.routeModeId);
  }
  if (run.teleportationItems) {
    appendKV(kv, "Teleports", run.teleportationItems);
  }
  if (run.includeBankPath != null) {
    appendKV(kv, "Bank route", run.includeBankPath ? "on" : "off");
  }
  if (run.useTeleportationMinigames != null) {
    appendKV(kv, "Minigame teleports", run.useTeleportationMinigames ? "on" : "off");
  }
  appendKV(kv, "Reached", run.reached ? "yes" : "no");
  appendKV(kv, "Assertion",
    run.assertionPassed === true ? "passed" :
    run.assertionPassed === false ? "failed" : "n/a");
  if (run.assertionMessage) {
    appendKV(kv, "Assertion msg", run.assertionMessage);
  }
  appendKV(kv, "Termination", run.terminationReason);
  appendKV(kv, "Path length", run.path ? run.path.length : 0);
  appendKV(kv, "Elapsed", formatElapsed(run.stats && run.stats.elapsedNanos));
  if (run.category) {
    appendKV(kv, "Category", run.category);
  }
  runInfoOverviewEl.appendChild(kv);

  if (run.bankEvents && run.bankEvents.length > 0) {
    renderBankChipSection(runInfoOverviewEl, run, "Banks visited");
  } else if (run.bankVisitedOnPath === false && run.routeModeId === "BANK") {
    const empty = document.createElement("div");
    empty.className = "run-info-empty";
    empty.textContent = "Bank mode, but no bank was visited on the resolved path.";
    runInfoOverviewEl.appendChild(empty);
  }
}

function buildPathPane(run) {
  clearEl(runInfoPathEl);

  const kv = document.createElement("dl");
  kv.className = "run-info-kv";
  appendKV(kv, "Start", formatPoint(run.start));
  appendKV(kv, "Target", formatPoint(run.target));
  if (run.closestReachedPoint) {
    appendKV(kv, "Closest", formatPoint(run.closestReachedPoint));
  }
  appendKV(kv, "Nodes", run.stats && run.stats.nodesChecked);
  appendKV(kv, "Transports", run.stats && run.stats.transportsChecked);
  runInfoPathEl.appendChild(kv);

  if (run.bankEvents && run.bankEvents.length > 0) {
    renderBankChipSection(runInfoPathEl, run, "Bank events");
  }

  if (run.transports && run.transports.length > 0) {
    runInfoPathEl.appendChild(makeSectionTitle(`Transports used (${run.transports.length})`));
    const chips = document.createElement("div");
    chips.className = "run-info-chips";
    run.transports.forEach(step => {
      const label = step.displayInfo || step.objectInfo || step.type;
      const kind = (step.type || "").toLowerCase().includes("teleport") ? "teleport" : "exit";
      chips.appendChild(makeChip(kind, label, `step ${step.stepIndex}`, () => {
        if (step.destination) {
          flyAndFlash(step.destination);
        } else if (step.origin) {
          flyAndFlash(step.origin);
        }
      }));
    });
    runInfoPathEl.appendChild(chips);
  }

  if (!run.bankEvents?.length && !run.transports?.length) {
    const empty = document.createElement("div");
    empty.className = "run-info-empty";
    empty.textContent = "No transports or bank stops on this path.";
    runInfoPathEl.appendChild(empty);
  }
}

// Bank tab is shown only when the run has at least one bank event that actually picked up items.
// Renders bank chips plus the per-bank pickup list with interchangeable equivalents (combo runes,
// staves, tomes). Equivalents sourced from ItemVariations so missing entries surface as raw
// humanised ItemID constants, making gaps in the data auditable at a glance.
function buildBankPane(run) {
  clearEl(runInfoBankEl);
  const events = (run.bankEvents || []).filter(ev => ev.pickups && ev.pickups.length);
  const hasContent = events.length > 0;
  if (bankTabButton) bankTabButton.hidden = !hasContent;
  if (!hasContent) {
    return false;
  }
  renderBankChipSection(runInfoBankEl, run, "Bank events");
  renderPickupsFor(runInfoBankEl, events);
  return true;
}

function setActiveTab(tabId) {
  // If the requested tab is hidden (e.g. the Bank tab when the run has no pickups), fall back to
  // the first visible tab — otherwise clicking a run that was viewed on "bank" would leave the
  // scenario panel blank on runs without pickups.
  const target = runInfoTabButtons.find(btn => btn.dataset.tab === tabId && !btn.hidden)
              || runInfoTabButtons.find(btn => !btn.hidden);
  const activeId = target ? target.dataset.tab : tabId;
  runInfoTabButtons.forEach(btn => {
    btn.classList.toggle("active", btn.dataset.tab === activeId);
  });
  runInfoTabPanes.forEach(pane => {
    pane.hidden = pane.dataset.tab !== activeId;
  });
}

async function copyCoordinate(point) {
  const text = formatCoordinateText(point);
  if (navigator.clipboard && navigator.clipboard.writeText) {
    await navigator.clipboard.writeText(text);
    return true;
  }

  const input = document.createElement("textarea");
  input.value = text;
  input.setAttribute("readonly", "");
  input.style.position = "absolute";
  input.style.left = "-9999px";
  document.body.appendChild(input);
  input.select();
  let copied = false;
  try {
    copied = document.execCommand("copy");
  } finally {
    document.body.removeChild(input);
  }
  return copied;
}

function normalizeSearchText(text) {
  return (text || "").toLowerCase().replace(/\s+/g, " ").trim();
}

function buildRunSearchText(run) {
  const bankSearch = (run.bankEvents || []).map(ev => `${ev.bankName || ""} step ${ev.stepIndex}`).join(" ");
  const parts = [
    run.name,
    run.category,
    run.routeModeId,
    run.teleportationItems,
    run.terminationReason,
    ...(run.details || []),
    ...(run.transports || []).map(step => `${step.type} ${step.displayInfo || step.objectInfo || ""}`),
    bankSearch
  ];
  return normalizeSearchText(parts.join(" "));
}

function fuzzyScore(query, text) {
  if (!query) {
    return 0;
  }

  if (text.includes(query)) {
    return query.length * 1000 - text.indexOf(query);
  }

  let score = 0;
  let textIndex = 0;
  let consecutive = 0;
  for (let i = 0; i < query.length; i++) {
    const ch = query[i];
    const foundIndex = text.indexOf(ch, textIndex);
    if (foundIndex === -1) {
      return Number.NEGATIVE_INFINITY;
    }

    if (foundIndex === textIndex) {
      consecutive += 1;
      score += 20 + consecutive * 5;
    } else {
      consecutive = 0;
      score += 5;
    }

    textIndex = foundIndex + 1;
  }

  return score - (text.length - query.length);
}

function filteredRuns() {
  const baseRuns = unreachedOnlyEl.checked ? allRuns.filter(run => !run.reached) : allRuns;
  const query = normalizeSearchText(runSearchEl.value);
  if (!query) {
    return baseRuns;
  }

  return baseRuns
    .map(run => ({ run, score: fuzzyScore(query, buildRunSearchText(run)) }))
    .filter(entry => entry.score > Number.NEGATIVE_INFINITY)
    .sort((a, b) => b.score - a.score || a.run.name.localeCompare(b.run.name))
    .map(entry => entry.run);
}

function updateRunNavigation() {
  const runs = filteredRuns();
  const selectedIndex = selectedRun ? runs.indexOf(selectedRun) : -1;
  const hasSelection = selectedIndex !== -1;

  prevRunEl.disabled = !hasSelection || selectedIndex === 0;
  nextRunEl.disabled = !hasSelection || selectedIndex === runs.length - 1;
}

function renderRunList() {
  const runs = filteredRuns();
  runListEl.innerHTML = "";

  if (runs.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No matching scenarios.";
    runListEl.appendChild(item);
    updateRunNavigation();
    return;
  }

  runs.forEach(run => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    const dot = document.createElement("span");
    dot.className = "run-status-dot " + (run.reached ? "run-status-dot--reached" : "run-status-dot--unreached");
    dot.title = statusLabel(run);
    button.appendChild(dot);
    button.appendChild(document.createTextNode(run.name));
    if (selectedRun === run) {
      button.classList.add("selected");
    }
    button.addEventListener("click", () => selectRun(run));
    item.appendChild(button);
    runListEl.appendChild(item);
  });

  updateRunNavigation();
}

// ── Extension hook for optional panels (e.g. profiler) ──────────────
// Extensions register via window.dashboardExtensions.push({ renderRun: fn })
window.dashboardExtensions = [];

function renderRun(run) {
  selectedRun = run;
  clearLayers();
  currentPlane = run.target.plane;
  baseLayer.redraw();
  map.fire("planechange");

  const pathSegments = buildPathSegments(run.path || []);
  if (pathSegments.length > 0) {
    const polylines = pathSegments.map(segment => {
      const polyline = L.polyline(segment.points.map(worldToLatLng), {
        color: pathSegmentColor(run.reached, segment.bankVisited),
        weight: 3
      }).addTo(map);
      markerLayers.push(polyline);
      return polyline;
    });
    const group = L.featureGroup(polylines);
    map.fitBounds(group.getBounds().pad(0.25));
  } else {
    map.setView(worldToLatLng(run.target), 2);
  }

  (run.markers || []).forEach(marker => {
    const radius = marker.kind === "bank" ? 5 : 6;
    addMarker(marker.point, marker.label, markerColor(marker.kind), radius);
  });

  runInfoDetailsEl.textContent = buildDetails(run);
  buildOverviewPane(run);
  buildPathPane(run);
  buildBankPane(run);
  // If the currently-active tab was hidden by buildBankPane (e.g. switched runs), normalise.
  const activeBtn = runInfoTabButtons.find(btn => btn.classList.contains("active"));
  if (activeBtn && activeBtn.hidden) setActiveTab("overview");
  runInfoPanel.classList.remove("run-info-hidden");
  setPathLegendVisible(Boolean(run.bankVisitedOnPath) || (run.bankEvents && run.bankEvents.length > 0));
  renderTransportOverlays();
  window.dashboardExtensions.forEach(ext => ext.renderRun(run));
}

function updateUrl() {
  const params = new URLSearchParams();
  const bundle = loadedBundles.find(b => b.reportPath === bundleSelectEl.value);
  if (bundle) {
    params.set("bundle", bundle.name);
  }
  if (selectedRun) {
    const idx = allRuns.indexOf(selectedRun);
    if (idx !== -1) {
      params.set("route", idx);
    }
  }
  const center = map.getCenter();
  const point = latLngToWorldPoint(center);
  params.set("x", point.x);
  params.set("y", point.y);
  params.set("zoom", map.getZoom());
  history.replaceState(null, "", `${window.location.pathname}?${params}`);
}

function selectRun(run) {
  renderRun(run);
  renderRunList();
  updateUrl();
}

function navigateRun(delta) {
  const runs = filteredRuns();
  const index = selectedRun ? runs.indexOf(selectedRun) : -1;
  const next = index + delta;
  if (index === -1 || next < 0 || next >= runs.length) return;
  selectRun(runs[next]);
}

function renderReport(report) {
  const runs = report.runs || [];
  reportTransportLayers = report.transportLayers || [];
  allRuns = runs;
  selectedRun = null;
  clearLayers();
  applyLeagueRegionsOverlay(Boolean(report.seasonal));
  applyF2pRegionsOverlay(Boolean(report.f2p));
  const summaryLines = [];
  if (report.title) {
    summaryLines.push(report.title);
  }
  if (report.scenarioId) {
    summaryLines.push(
      `Scenario: ${report.scenarioId}` +
      (report.scenarioDefaultStart ? ` @ ${formatPoint(report.scenarioDefaultStart)}` : "")
    );
  }
  summaryLines.push(
    `${report.summary.successfulRuns}/${report.summary.totalRuns} reached, ` +
    `${report.summary.failedRuns} unreached`
  );
  if (report.subtitle) {
    summaryLines.push(report.subtitle);
  }
  summaryEl.textContent = summaryLines.join("\n");
  if (report.bankNamesFromData && report.bankNamesFromData.length) {
    summaryEl.title =
      `${report.bankNamesFromData.length} known banks (bank.tsv):\n` +
      report.bankNamesFromData.join(", ");
  } else {
    summaryEl.removeAttribute("title");
  }

  if (runs.length === 0) {
    summaryEl.textContent = summaryEl.textContent + "\nNo runs in report.json";
    runListEl.innerHTML = "";
    return;
  }

  currentPlane = runs[0].target.plane;
  baseLayer.redraw();
  map.fire("planechange");
  selectRun(runs[0]);
}

async function loadReport(url) {
  currentBundleBase = url.substring(0, url.lastIndexOf("/") + 1);
  window.currentBundleBase = currentBundleBase;
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to load ${url}`);
  }
  const report = await response.json();
  renderReport(report);
}

async function initDashboard() {
  const indexResp = await fetch("bundles/index.json");
  if (!indexResp.ok) {
    throw new Error("No bundles/index.json found. Run a dashboard task first.");
  }
  const index = await indexResp.json();
  if (!index.bundles || index.bundles.length === 0) {
    throw new Error("No bundles registered in index.json.");
  }

  loadedBundles = index.bundles;

  // Populate bundle selector
  bundleSelectEl.innerHTML = "";
  for (const bundle of index.bundles) {
    const opt = document.createElement("option");
    opt.value = bundle.reportPath;
    opt.textContent = bundle.title || bundle.name;
    bundleSelectEl.appendChild(opt);
  }
  if (index.bundles.length > 1) {
    bundleSelectEl.hidden = false;
  }
  bundleSelectEl.addEventListener("change", async () => {
    try {
      await loadReport("bundles/" + bundleSelectEl.value);
    } catch (error) {
      summaryEl.textContent = error.message;
    }
    updateUrl();
  });

  const params = new URLSearchParams(window.location.search);
  const requested = params.get("bundle");
  const initial = (requested && index.bundles.find(b => b.name === requested)) || index.bundles[0];
  bundleSelectEl.value = initial.reportPath;
  await loadReport("bundles/" + initial.reportPath);

  // Auto-select route by index (?route=<index>)
  const routeParam = params.get("route");
  if (routeParam !== null) {
    const routeIndex = parseInt(routeParam, 10);
    if (Number.isFinite(routeIndex) && routeIndex >= 0 && routeIndex < allRuns.length) {
      selectRun(allRuns[routeIndex]);
    }
  }

  // Auto-position map (?x=<x>&y=<y>&zoom=<zoom>)
  const xParam = params.get("x");
  const yParam = params.get("y");
  if (xParam !== null && yParam !== null) {
    const x = parseFloat(xParam);
    const y = parseFloat(yParam);
    if (Number.isFinite(x) && Number.isFinite(y)) {
      const zoomParam = params.get("zoom");
      const zoom = (zoomParam !== null && Number.isFinite(parseFloat(zoomParam)))
        ? parseFloat(zoomParam)
        : 2;
      map.setView(worldToLatLng({ x, y }), zoom, { animate: false });
    }
  }
}

initDashboard().catch(error => {
  summaryEl.textContent = error.message;
});

// ── Theme toggle ──────────────────────────────────────────────────────
(function () {
  const SUN_ICON  = '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>';
  const MOON_ICON = '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>';
  const html = document.documentElement;
  const btn  = document.getElementById("theme-toggle");
  const saved = (() => { try { return localStorage.getItem("theme"); } catch { return null; } })();
  const theme = saved || "dark";
  html.setAttribute("data-theme", theme);
  btn.innerHTML = theme === "dark" ? SUN_ICON : MOON_ICON;
  btn.title = theme === "dark" ? "Switch to light mode" : "Switch to dark mode";
  btn.addEventListener("click", () => {
    const next = html.getAttribute("data-theme") === "dark" ? "light" : "dark";
    html.setAttribute("data-theme", next);
    btn.innerHTML = next === "dark" ? SUN_ICON : MOON_ICON;
    btn.title = next === "dark" ? "Switch to light mode" : "Switch to dark mode";
    try { localStorage.setItem("theme", next); } catch {}
  });
})();

// ── Event listeners ───────────────────────────────────────────────────

// Run info panel toggle (right side of map)
runInfoToggle.addEventListener("click", () => {
  const collapsed = runInfoPanel.classList.toggle("collapsed");
  runInfoToggle.innerHTML = (collapsed ? "&#9656;" : "&#9666;") + " Scenario";
});

// Tab bar inside the run info panel
runInfoTabButtons.forEach(btn => {
  btn.addEventListener("click", () => setActiveTab(btn.dataset.tab));
});

// Sidebar toggle — on narrow viewports the sidebar defaults to collapsed via CSS
// (.sidebar:not(.expanded) rule), so we toggle the explicit "expanded" class there;
// on wider viewports we toggle "collapsed".
const narrowSidebarQuery = window.matchMedia("(max-width: 1100px)");
const sidebarToggleIcon = sidebarToggle.querySelector("span");

function sidebarIsShowing() {
  return narrowSidebarQuery.matches
    ? sidebarEl.classList.contains("expanded")
    : !sidebarEl.classList.contains("collapsed");
}

function syncSidebarToggleIcon() {
  sidebarToggleIcon.innerHTML = sidebarIsShowing() ? "&#9664;" : "&#9654;";
}

sidebarToggle.addEventListener("click", () => {
  const cls = narrowSidebarQuery.matches ? "expanded" : "collapsed";
  sidebarEl.classList.toggle(cls);
  syncSidebarToggleIcon();
  setTimeout(() => map.invalidateSize(), 250);
});
narrowSidebarQuery.addEventListener("change", syncSidebarToggleIcon);
syncSidebarToggleIcon();

function onFilterChange() {
  const runs = filteredRuns();
  renderRunList();
  if (runs.length === 0) {
    selectedRun = null;
    clearLayers();
    return;
  }
  if (!selectedRun || !runs.includes(selectedRun)) {
    selectRun(runs[0]);
  }
}

runSearchEl.addEventListener("input", onFilterChange);
unreachedOnlyEl.addEventListener("change", onFilterChange);

centerTargetEl.addEventListener("click", () => {
  if (!selectedRun) return;
  map.setView(worldToLatLng(selectedRun.target), Math.max(map.getZoom(), 2));
});

prevRunEl.addEventListener("click", () => navigateRun(-1));
nextRunEl.addEventListener("click", () => navigateRun(1));

map.on("moveend", updateUrl);

map.on("mousemove", event => {
  const point = latLngToWorldPoint(event.latlng);
  updateMapCoordinates(point);
  renderHoveredTile(point);
});

map.on("mouseout", () => {
  mapCoordinatesEl.textContent = "-, -, -";
  if (hoveredTileOverlay) {
    map.removeLayer(hoveredTileOverlay);
    hoveredTileOverlay = null;
  }
});

map.on("click", async event => {
  if (!event.originalEvent || !event.originalEvent.ctrlKey) {
    return;
  }

  const point = latLngToWorldPoint(event.latlng);
  const copied = await copyCoordinate(point);
  updateMapCoordinates(point, copied);
  if (copied) {
    console.log(`Copied coordinate: ${formatCoordinateText(point)}`);
  }
});
