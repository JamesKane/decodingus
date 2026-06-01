// Biosample map: initialize Leaflet, load GeoJSON from the data-geo URL, and
// plot circle markers (no marker-image assets needed).
document.addEventListener("DOMContentLoaded", function () {
  var el = document.getElementById("map");
  if (!el || typeof L === "undefined") return;

  var map = L.map("map").setView([20, 0], 2);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 18,
    attribution: "&copy; OpenStreetMap contributors",
  }).addTo(map);

  fetch(el.dataset.geo)
    .then(function (r) { return r.json(); })
    .then(function (fc) {
      var layer = L.geoJSON(fc, {
        pointToLayer: function (_f, latlng) {
          return L.circleMarker(latlng, {
            radius: 6, color: "#0d6efd", weight: 1, fillOpacity: 0.7,
          });
        },
        onEachFeature: function (f, l) {
          var p = f.properties || {};
          l.bindPopup((p.accession || "—") + " (" + (p.source || "") + ")");
        },
      }).addTo(map);

      var count = (fc.features || []).length;
      var badge = document.getElementById("map-count");
      if (badge) badge.textContent = count;
      if (count > 0) map.fitBounds(layer.getBounds().pad(0.2));
    })
    .catch(function (e) { console.error("geo-data load failed", e); });
});
