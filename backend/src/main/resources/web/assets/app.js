import { messages } from "./messages.js";
import { createRoutePlanner } from "./routes.js";
import { createStatistics } from "./statistics.js";

const styleUrl = "https://tiles.openfreemap.org/styles/liberty";
const source = "observations";
const colors = {
    good: "#2e7d32", fair: "#f9a825", poor: "#ef6c00", bad: "#c62828",
    pothole: "#212121", speed_bump: "#1565c0",
};
const quality = new Set(["good", "fair", "poor", "bad"]);
const eventTypes = new Set(["pothole", "speed_bump"]);
const pilotBounds = [[-46.89, -23.60], [-46.61, -23.48]];
const maxBoxSpan = 1;
const maxPages = 20;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const collection = features => ({ type: "FeatureCollection", features });
const boundedCoordinate = (value, max) => +Math.max(-max, Math.min(max, value)).toFixed(6);

const refresh = document.querySelector("#refresh");
const status = document.querySelector("#status");
const speedToggle = document.querySelector("#speeds");
const filters = document.querySelectorAll("[name=layer-filter]");
const localeButtons = document.querySelectorAll("[data-language]");
const tabs = [...document.querySelectorAll("[role=tab][data-view]")];
const mapAbout = document.querySelector("#map-about");
const statisticsRoot = document.createElement("div");
const statistics = createStatistics(statisticsRoot);

let language = "pt-BR";
let activeView = "map";
let map;
let routePlanner;
let mapReady = false;
let filter = "all";
let selected;
let popup;
let pending;
let timer;
let statusKey = "mapLoading";
let statusError = false;

const t = key => messages[language][key];
const number = value => new Intl.NumberFormat(language, { maximumFractionDigits: 1 }).format(value);
const speed = value => `${number(value)} km/h`;

function showStatus(key, error = false) {
    statusKey = key;
    statusError = error;
    status.textContent = t(key);
    status.dataset.error = String(error);
}

function updateMapLabels() {
    const labels = {
        ".maplibregl-ctrl-zoom-in": "zoomIn",
        ".maplibregl-ctrl-zoom-out": "zoomOut",
        ".maplibregl-ctrl-attrib-button": "toggleAttribution",
    };
    for (const [selector, key] of Object.entries(labels)) {
        const control = document.querySelector(selector);
        control?.setAttribute("aria-label", t(key));
        control?.setAttribute("title", t(key));
    }
    map?.getCanvas().setAttribute("aria-label", t("mapTitle"));
    popup?.getElement().querySelector(".maplibregl-popup-close-button")?.setAttribute("aria-label", t("close"));
}

function setLanguage(value) {
    language = value;
    document.documentElement.lang = value;
    document.title = `BumpMap — ${t("map")}`;
    for (const node of document.querySelectorAll("[data-text]")) node.textContent = t(node.dataset.text);
    for (const node of document.querySelectorAll("[data-label]")) node.setAttribute("aria-label", t(node.dataset.label));
    for (const button of localeButtons) button.setAttribute("aria-pressed", String(button.dataset.language === value));
    showStatus(statusKey, statusError);
    statistics.setLanguage(value);
    routePlanner?.setLanguage(value);
    if (popup && selected) popup.setDOMContent(details(selected));
    updateMapLabels();
}

function validPosition(position) {
    return Array.isArray(position) && position.length === 2 && position.every(Number.isFinite) &&
        Math.abs(position[0]) <= 180 && Math.abs(position[1]) <= 90;
}

function parseFeature(feature) {
    const { type, id, geometry, properties: data } = feature ?? {};
    if (type !== "Feature" || typeof id !== "string" || !uuid.test(id) ||
        !geometry || !data || !Number.isFinite(data.speed_kmh) || data.speed_kmh < 0) return null;
    const { kind, speed_kmh } = data;
    const { type: shape, coordinates } = geometry;
    let properties;
    if (kind === "segment" && quality.has(data.map_quality) && shape === "LineString" &&
        Array.isArray(coordinates) && coordinates.length >= 2 && coordinates.every(validPosition)) {
        const { map_quality, penalty_points, potholes, speed_bumps } = data;
        if (!Number.isFinite(penalty_points) || penalty_points < 0 ||
            ![potholes, speed_bumps].every(value => Number.isSafeInteger(value) && value >= 0)) return null;
        properties = { kind, speed_kmh, map_quality, penalty_points, potholes, speed_bumps };
    } else if (kind === "event" && eventTypes.has(data.type) && shape === "Point" && validPosition(coordinates)) {
        properties = { kind, type: data.type, speed_kmh };
    } else {
        return null;
    }
    return { type, id, geometry: { type: shape, coordinates }, properties };
}

function viewportBox() {
    const bounds = map.getBounds();
    const raw = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()];
    if (!raw.every(Number.isFinite)) return { error: "invalidViewport" };
    const box = raw.map((value, i) => boundedCoordinate(value, i % 2 === 0 ? 180 : 90));
    const [west, south, east, north] = box;
    if (east <= west || north <= south) return { error: "invalidViewport" };
    if (east - west > maxBoxSpan + Number.EPSILON * 180 || north - south > maxBoxSpan + Number.EPSILON * 180) {
        return { error: "zoom" };
    }
    return { bbox: box.join(",") };
}

async function getPage(bbox, after, signal) {
    const params = new URLSearchParams({ bbox });
    if (after) params.set("after", after);
    let response;
    try {
        response = await fetch(`/v1/public/map/observations?${params}`, {
            headers: { Accept: "application/geo+json, application/json" },
            cache: "no-store",
            credentials: "omit",
            redirect: "error",
            signal,
        });
    } catch {
        throw new Error("networkError");
    }
    if (response.status === 429) throw new Error("rateLimit");
    if (response.status >= 500) throw new Error("serverError");
    if (!response.ok) throw new Error("requestError");

    let page;
    try {
        page = await response.json();
    } catch {
        throw new Error("malformed");
    }
    if (page?.type !== "FeatureCollection" || !Array.isArray(page.features) ||
        (page.next_cursor != null && (typeof page.next_cursor !== "string" || !uuid.test(page.next_cursor)))) {
        throw new Error("malformed");
    }
    return page;
}

function cancelRequest() {
    clearTimeout(timer);
    pending?.abort();
    pending = undefined;
}

function clearMap() {
    popup?.remove();
    map?.getSource(source)?.setData(collection([]));
}

async function loadViewport() {
    cancelRequest();
    if (!mapReady || activeView !== "map") return;
    const { bbox, error } = viewportBox();
    if (error) {
        showStatus(error, error !== "zoom");
        return;
    }

    const request = pending = new AbortController();
    const features = new Map();
    const cursors = new Set();
    let after;
    showStatus("loading");
    try {
        for (let i = 0; i < maxPages; i++) {
            const page = await getPage(bbox, after, request.signal);
            if (pending !== request) return;
            for (const entry of page.features) {
                const feature = parseFeature(entry);
                if (feature && !features.has(feature.id)) features.set(feature.id, feature);
            }
            after = page.next_cursor;
            if (!after) break;
            if (cursors.has(after)) throw new Error("malformed");
            cursors.add(after);
        }
        map.getSource(source).setData(collection([...features.values()]));
        showStatus(after ? "limited" : features.size ? "loaded" : "empty");
    } catch (error) {
        if (pending !== request) return;
        showStatus(Object.hasOwn(messages[language], error.message) ? error.message : "requestError", true);
    } finally {
        if (pending === request) pending = undefined;
    }
}

function details(data) {
    const content = document.createElement("div");
    const title = document.createElement("h3");
    const list = document.createElement("dl");
    const segment = data.kind === "segment";
    title.textContent = t(segment ? "section" : data.type);
    const rows = segment ? [
        ["quality", t(data.map_quality)], ["meanSpeed", speed(data.speed_kmh)],
        ["points", number(data.penalty_points)], ["potholePlural", number(data.potholes)],
        ["bumpPlural", number(data.speed_bumps)],
    ] : [["speed", speed(data.speed_kmh)]];
    for (const [label, value] of rows) {
        const term = document.createElement("dt");
        const desc = document.createElement("dd");
        term.textContent = t(label);
        desc.textContent = value;
        list.append(term, desc);
    }
    content.append(title, list);
    return content;
}

function queryFeatures(point) {
    return map.queryRenderedFeatures(point, {
        layers: filter === "all" ? ["road-events", "measured-roads"] : ["road-events"],
    });
}

function inspect(event) {
    const feature = queryFeatures(event.point)[0];
    if (!feature) return;
    popup?.remove();
    selected = feature.properties;
    popup = new maplibregl.Popup({ className: "observation-popup" })
        .setLngLat(selected.kind === "event" ? feature.geometry.coordinates : event.lngLat)
        .setDOMContent(details(selected))
        .addTo(map);
    popup.on("close", () => { popup = selected = undefined; });
    updateMapLabels();
}

function updateLayers() {
    if (!mapReady) return;
    popup?.remove();
    const all = filter === "all";
    for (const layer of ["measured-roads", "road-casing"]) {
        map.setLayoutProperty(layer, "visibility", all ? "visible" : "none");
    }
    map.setFilter("road-events", all ? isEvent : ["all", isEvent, ["==", ["get", "type"], filter]]);
    map.setFilter("event-speeds", map.getFilter("road-events"));
    map.setLayoutProperty("road-speeds", "visibility", all && speedToggle.checked ? "visible" : "none");
    map.setLayoutProperty("event-speeds", "visibility", speedToggle.checked ? "visible" : "none");
}

const isSegment = ["==", ["get", "kind"], "segment"];
const isEvent = ["==", ["get", "kind"], "event"];

function addObservationLayers() {
    map.addSource(source, { type: "geojson", data: collection([]) });
    const lineLayout = { "line-join": "round", "line-cap": "round" };
    map.addLayer({
        id: "road-casing", source, type: "line", filter: isSegment, layout: lineLayout,
        paint: { "line-color": "#fff", "line-width": 8 },
    });
    map.addLayer({
        id: "measured-roads", source, type: "line", filter: isSegment, layout: lineLayout,
        paint: {
            "line-width": 5,
            "line-color": ["match", ["get", "map_quality"],
                "good", colors.good, "fair", colors.fair, "poor", colors.poor, "bad", colors.bad, "#777"],
        },
    });
    map.addLayer({
        id: "road-events", source, type: "circle", filter: isEvent,
        paint: {
            "circle-radius": 6, "circle-stroke-width": 2, "circle-stroke-color": "#fff",
            "circle-color": ["match", ["get", "type"], "speed_bump", colors.speed_bump, colors.pothole],
        },
    });
    const labelLayout = {
        "text-field": ["concat", ["to-string", ["round", ["get", "speed_kmh"]]], " km/h"],
        "text-font": ["Noto Sans Regular"], "text-size": 11, "text-anchor": "bottom", "text-offset": [0, -0.85],
        visibility: "none",
    };
    const labelPaint = { "text-color": "#102c28", "text-halo-color": "#fff", "text-halo-width": 1.5 };
    map.addLayer({
        id: "road-speeds", source, type: "symbol", filter: isSegment, paint: labelPaint,
        layout: { ...labelLayout, "symbol-placement": "line-center" },
    });
    map.addLayer({
        id: "event-speeds", source, type: "symbol", filter: isEvent, paint: labelPaint, layout: labelLayout,
    });
}

function initMap() {
    if (!window.maplibregl) {
        showStatus("mapError", true);
        return;
    }
    try {
        map = new maplibregl.Map({
            container: "map", style: styleUrl, bounds: pilotBounds,
            fitBoundsOptions: { padding: 24, maxZoom: 12 },
            renderWorldCopies: false, dragRotate: false, pitchWithRotate: false,
        });
    } catch {
        showStatus("mapError", true);
        return;
    }
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    updateMapLabels();
    map.on("error", () => {
        if (!mapReady) {
            showStatus("mapError", true);
        }
    });
    map.on("load", () => {
        addObservationLayers();
        mapReady = true;
        routePlanner = createRoutePlanner({
            root: document.querySelector("#route-planner"),
            map,
        });
        routePlanner.setLanguage(language);
        updateLayers();
        updateMapLabels();
        loadViewport();
    });
    map.on("movestart", () => {
        cancelRequest();
        if (!mapReady || activeView !== "map") return;
        clearMap();
        showStatus("moving");
    });
    map.on("moveend", () => {
        if (mapReady && activeView === "map") {
            clearTimeout(timer);
            timer = setTimeout(loadViewport, 250);
        }
    });
    map.on("click", event => {
        if (!mapReady) return;
        if (routePlanner?.handleMapClick(event)) popup?.remove();
        else inspect(event);
    });
    map.on("mousemove", event => {
        if (!mapReady) return;
        const cursor = routePlanner?.isPicking?.() ? "crosshair" :
            queryFeatures(event.point).length ? "pointer" : "";
        map.getCanvas().style.cursor = cursor;
    });
}

function switchView(view) {
    if (activeView === view) return;
    activeView = view;
    for (const tab of tabs) {
        const active = tab.dataset.view === view;
        tab.tabIndex = active ? 0 : -1;
        tab.setAttribute("aria-selected", String(active));
        document.getElementById(tab.getAttribute("aria-controls")).hidden = !active;
    }
    mapAbout.hidden = view !== "map";
    if (view === "map") {
        statistics.hide();
        map?.resize();
        loadViewport();
    } else {
        cancelRequest();
        document.getElementById(`view-${view}`).append(statisticsRoot);
        statistics.show(view);
    }
}

refresh.addEventListener("click", () => {
    if (activeView !== "map") {
        statistics.refresh();
        return;
    }
    clearMap();
    loadViewport();
});
speedToggle.addEventListener("change", updateLayers);
for (const control of filters) {
    control.addEventListener("change", () => {
        filter = control.value;
        updateLayers();
    });
}
for (const button of localeButtons) {
    button.addEventListener("click", () => setLanguage(button.dataset.language));
}
for (const [index, tab] of tabs.entries()) {
    tab.addEventListener("click", () => switchView(tab.dataset.view));
    tab.addEventListener("keydown", event => {
        let next;
        switch (event.key) {
            case "ArrowLeft": next = (index + tabs.length - 1) % tabs.length; break;
            case "ArrowRight": next = (index + 1) % tabs.length; break;
            case "Home": next = 0; break;
            case "End": next = tabs.length - 1; break;
            default: return;
        }
        event.preventDefault();
        tabs[next].focus();
    });
}
initMap();
document.querySelector("#view-cities").append(statisticsRoot);
statistics.show("cities");
