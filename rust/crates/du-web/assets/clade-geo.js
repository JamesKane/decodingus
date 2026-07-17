// Clade "Geography & Time" panel: when the SNP off-canvas swaps in the geo fragment
// (#cladeGeo), lazily load Leaflet and render the clade's sample origins. Ancient
// samples (properties.ancient) are styled distinctly from modern ones. Idempotent and
// event-driven so it survives hx-boost body swaps (mirrors tree.js).
(function () {
  if (window.__duCladeGeo) return;
  window.__duCladeGeo = true;

  // Leaflet isn't loaded on the tree page by default — inject it on first use.
  var leafletPromise = null;
  function ensureLeaflet() {
    if (window.L) return Promise.resolve();
    if (leafletPromise) return leafletPromise;
    leafletPromise = new Promise(function (resolve, reject) {
      var css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = "/assets/vendor/leaflet.css";
      document.head.appendChild(css);
      var s = document.createElement("script");
      s.src = "/assets/vendor/leaflet.js";
      s.onload = function () { resolve(); };
      s.onerror = function () { reject(new Error("leaflet load failed")); };
      document.head.appendChild(s);
    });
    return leafletPromise;
  }

  function markerStyle(props) {
    return (props && props.ancient)
      ? { radius: 6, color: "#8a4b12", weight: 1, fillColor: "#e08a3c", fillOpacity: 0.9 }
      : { radius: 5, color: "#0d6efd", weight: 1, fillColor: "#0d6efd", fillOpacity: 0.6 };
  }

  function initMap(el) {
    if (!el || el.dataset.inited) return;
    el.dataset.inited = "1";
    el.textContent = "";
    ensureLeaflet().then(function () {
      var map = L.map(el, { worldCopyJump: true }).setView([25, 10], 1);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 18,
        attribution: "&copy; OpenStreetMap contributors",
      }).addTo(map);

      fetch(el.dataset.geoUrl)
        .then(function (r) { return r.json(); })
        .then(function (fc) {
          var layer = L.geoJSON(fc, {
            pointToLayer: function (f, ll) { return L.circleMarker(ll, markerStyle(f.properties)); },
            onEachFeature: function (f, l) {
              var p = f.properties || {};
              l.bindPopup((p.accession || "—") + " (" + (p.source || "") + ")");
            },
          }).addTo(map);
          if ((fc.features || []).length > 0) {
            map.fitBounds(layer.getBounds().pad(0.2));
          }
          // The off-canvas may have animated in after L.map() measured a 0-size box.
          map.invalidateSize();
        })
        .catch(function (e) { console.error("clade geo-data load failed", e); });
    }).catch(function (e) {
      console.error(e);
      el.textContent = "map unavailable";
    });
  }

  document.addEventListener("htmx:afterSwap", function (e) {
    var t = e.detail && e.detail.target;
    if (t && t.id === "cladeGeo") {
      initMap(t.querySelector("#clade-map"));
    }
  });
})();
