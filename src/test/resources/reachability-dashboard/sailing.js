/* Sailing view: draws a route's experimental sailing search (run.sailing) next to the normal path.
 *
 * app.js draws the normal path. This adds the sailing path in blue, the boat's hull at each point where the
 * path turns (facing the leg that leaves it) and the area the hull sweeps along each leg, which is what the
 * search keeps clear of blocked tiles, plus a card comparing the two searches. Turn on the collision map
 * layer to see the tiles the hull has to clear.
 *
 * Registers itself via window.dashboardExtensions. */
(function () {
  const COLOR = "#1d6fe0";
  let layers = [];
  let showHull = true;
  let lastRun = null;
  let card = null;

  // Tiles are one map unit each; a path point is the centre of its tile
  function toLatLng(x, y) {
    return [y + 0.5, x + 0.5];
  }

  function clear() {
    const map = window._dashboardMap;
    layers.forEach(layer => map.removeLayer(layer));
    layers = [];
  }

  // Andrew's monotone chain, on [x, y] points
  function convexHull(points) {
    const sorted = points.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lower = [];
    for (const p of sorted) {
      while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop();
      lower.push(p);
    }
    const upper = [];
    for (let i = sorted.length - 1; i >= 0; i--) {
      const p = sorted[i];
      while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop();
      upper.push(p);
    }
    lower.pop();
    upper.pop();
    return lower.concat(upper);
  }

  // The hull's corners around a path point, from an outline relative to the point
  function corners(outline, point) {
    const result = [];
    for (let i = 0; i < outline.length; i += 2) {
      result.push([point.x + outline[i], point.y + outline[i + 1]]);
    }
    return result;
  }

  function addLayer(layer) {
    layer.addTo(window._dashboardMap);
    layers.push(layer);
  }

  function draw(run) {
    clear();
    updateCard(run);
    const sailing = run && run.sailing;
    if (!sailing || !sailing.path || sailing.path.length === 0) {
      return;
    }

    if (showHull) {
      for (let i = 0; i + 1 < sailing.path.length; i++) {
        const outline = sailing.outlines[i];
        if (!outline) continue;
        const swept = convexHull(corners(outline, sailing.path[i]).concat(corners(outline, sailing.path[i + 1])));
        addLayer(L.polygon(swept.map(p => toLatLng(p[0], p[1])), {
          stroke: false, fillColor: COLOR, fillOpacity: 0.12, interactive: false
        }));
      }
      sailing.path.forEach((point, i) => {
        const outline = sailing.outlines[i];
        if (!outline) return;
        addLayer(L.polygon(corners(outline, point).map(p => toLatLng(p[0], p[1])), {
          color: COLOR, weight: 1, fill: false, interactive: false
        }));
      });
    }

    addLayer(L.polyline(sailing.path.map(p => toLatLng(p.x, p.y)), { color: COLOR, weight: 3 }));
    sailing.path.forEach((point, i) => {
      addLayer(L.circleMarker(toLatLng(point.x, point.y), {
        radius: 3, color: COLOR, fillColor: COLOR, fillOpacity: 1
      }).bindTooltip(i === 0 ? "Sailing start" : `Sailing turn ${i}: ${point.x}, ${point.y}`));
    });
  }

  function formatMs(nanos) {
    return (nanos / 1e6).toFixed(1) + " ms";
  }

  function updateCard(run) {
    const map = window._dashboardMap;
    if (card) {
      map.removeControl(card);
      card = null;
    }
    const sailing = run && run.sailing;
    if (!sailing) return;
    const Card = L.Control.extend({
      options: { position: "bottomleft" },
      onAdd() {
        const div = L.DomUtil.create("div", "heatmap-legend leaflet-control");
        const title = L.DomUtil.create("div", "heatmap-legend-title", div);
        title.textContent = `Sailing: ${sailing.boat || "centre only"} at speed ${sailing.speed}`;
        const lines = [
          `Sailing path: ${Math.round(sailing.distance)} tiles, ${sailing.ticks} ticks, ${sailing.legs} legs` +
            (sailing.reached ? "" : " (not reached)"),
          `Existing path, as far: ${Math.round(sailing.normalDistance)} tiles, ~${Math.round(sailing.normalTicks)} ticks, ` +
            `${sailing.normalLegs} legs` + (sailing.boat ? `, ${sailing.normalCollisions} steps hit rocks` : ""),
          `Sailing search: ${sailing.nodesChecked.toLocaleString()} nodes, ${formatMs(sailing.elapsedNanos)}`,
          `Existing search: ${run.stats.nodesChecked.toLocaleString()} nodes, ${formatMs(run.stats.elapsedNanos)}`
        ];
        lines.forEach(text => {
          const row = L.DomUtil.create("div", "", div);
          row.textContent = text;
        });
        L.DomEvent.disableClickPropagation(div);
        return div;
      }
    });
    card = new Card();
    card.addTo(map);
  }

  function init() {
    if (typeof window.addMapLayerToggle !== "function" || !window._dashboardMap) {
      requestAnimationFrame(init);
      return;
    }
    window.addMapLayerToggle({
      label: "Sailing hull",
      checked: true,
      onChange(on) {
        showHull = !!on;
        draw(lastRun);
      }
    });
  }

  window.dashboardExtensions.push({
    renderRun(run) {
      lastRun = run;
      draw(run);
    }
  });

  if (document.readyState === "complete" || document.readyState === "interactive") {
    init();
  } else {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
