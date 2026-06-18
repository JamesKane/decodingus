// Sample report: render the origin map with Leaflet. Externalized from the inline
// <script> in samples/report.html; idempotent + event-driven for hx-boost.
(function () {
  if (window.__duReport) return;
  window.__duReport = true;

  function initMap() {
    var el = document.getElementById('sample-map');
    if (!el || el.dataset.mapInit || typeof L === 'undefined') return;
    var lat = parseFloat(el.dataset.lat), lon = parseFloat(el.dataset.lon);
    if (isNaN(lat) || isNaN(lon)) return;
    el.dataset.mapInit = '1';
    var m = L.map('sample-map').setView([lat, lon], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      { maxZoom: 18, attribution: '&copy; OpenStreetMap contributors' }).addTo(m);
    L.circleMarker([lat, lon], { radius: 7, color: '#0d6efd', weight: 1, fillOpacity: .8 }).addTo(m);
  }
  document.addEventListener('htmx:load', initMap);
  initMap();
})();
