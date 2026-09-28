/* Sailing view: draws a route's experimental sailing search (run.sailing) next to the normal path.
 *
 * app.js draws the normal path. This adds the sailing path in blue, with a dot where it turns, plus a card
 * comparing the two searches. Turn on the collision map layer to see the blocked tiles the path keeps off.
 *
 * Registers itself via window.dashboardExtensions. */
(function () {
  const COLOR = "#1d6fe0";
  let layers = [];
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
        title.textContent = `Sailing at speed ${sailing.speed}`;
        const lines = [
          `Sailing path: ${Math.round(sailing.distance)} tiles, ${sailing.ticks} ticks, ${sailing.legs} legs` +
            (sailing.reached ? "" : " (not reached)"),
          `Existing path, as far: ${Math.round(sailing.normalDistance)} tiles, ~${Math.round(sailing.normalTicks)} ticks, ` +
            `${sailing.normalLegs} legs`,
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

  window.dashboardExtensions.push({
    renderRun(run) {
      draw(run);
    }
  });
})();
