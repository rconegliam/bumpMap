const copy = {
    "pt-BR": {
        title: "Rota de carro",
        help: "Clique no mapa ou digite longitude, latitude em graus decimais, com ponto decimal.",
        from: "Origem",
        to: "Destino",
        pickFrom: "Marcar origem no mapa",
        pickTo: "Marcar destino no mapa",
        selectingFrom: "Clique no mapa para escolher a origem.",
        selectingTo: "Clique no mapa para escolher o destino.",
        build: "Traçar rota",
        clear: "Limpar",
        retry: "Tentar novamente",
        privacy: "A origem e o destino serão enviados a um provedor externo de rotas.",
        osmCredit: "Colaboradores do OpenStreetMap",
        fixMap: "Corrigir o mapa",
        configuring: "Verificando a disponibilidade de rotas…",
        configError: "Não foi possível verificar a disponibilidade de rotas.",
        loading: "Buscando rota…",
        invalid: "Informe duas coordenadas válidas e diferentes.",
        route_too_far: "Escolha pontos a até 1.000 km de distância em linha reta.",
        route_not_found: "Não encontrei uma rota de carro. Tente selecionar pontos próximos a vias.",
        route_busy: "O planejador está ocupado. Aguarde alguns segundos e tente novamente.",
        routing_disabled: "O serviço de rotas está desativado.",
        route_provider_unavailable: "O serviço de rotas está indisponível. Tente novamente em instantes.",
        route_provider_timeout: "A consulta demorou demais. Tente novamente.",
        route_provider_error: "Não foi possível carregar a rota. Tente novamente.",
        result: (distance, duration) => `${distance} · ${duration} estimados`,
    },
    en: {
        title: "Driving route",
        help: "Click the map or type longitude, latitude in decimal degrees, using a decimal point.",
        from: "Origin",
        to: "Destination",
        pickFrom: "Pick origin on the map",
        pickTo: "Pick destination on the map",
        selectingFrom: "Click the map to choose the origin.",
        selectingTo: "Click the map to choose the destination.",
        build: "Find route",
        clear: "Clear",
        retry: "Try again",
        privacy: "The origin and destination will be sent to an external routing provider.",
        osmCredit: "OpenStreetMap contributors",
        fixMap: "Fix the map",
        configuring: "Checking route availability…",
        configError: "Unable to check route availability.",
        loading: "Finding a route…",
        invalid: "Enter two valid, distinct coordinates.",
        route_too_far: "Choose points no more than 1,000 km apart as the crow flies.",
        route_not_found: "No driving route found. Try choosing points near roads.",
        route_busy: "The route planner is busy. Wait a few seconds and try again.",
        routing_disabled: "The routing service is disabled.",
        route_provider_unavailable: "The routing service is unavailable. Try again shortly.",
        route_provider_timeout: "The route request timed out. Try again.",
        route_provider_error: "Unable to load the route. Try again.",
        result: (distance, duration) => `${distance} · ${duration} estimated`,
    },
};

const sides = ["from", "to"];
const sourceId = "planned-car-route";
const routeUrl = "/v1/map/route";
const errorCodes = new Set([
    "route_too_far", "route_not_found", "route_busy", "routing_disabled",
    "route_provider_unavailable", "route_provider_timeout", "route_provider_error",
]);

function validPosition(point) {
    return Array.isArray(point) && point.length === 2 &&
        point.every(Number.isFinite) && Math.abs(point[0]) <= 180 && Math.abs(point[1]) <= 90;
}

function parsePoint(value) {
    const parts = value.split(",").map(part => part.trim());
    if (parts.length !== 2 || !parts.every(part => /^-?\d{1,3}(?:\.\d{1,8})?$/.test(part))) return null;
    const point = parts.map(Number);
    return validPosition(point) ? point : null;
}

function encodePoint(point) {
    return point.map(coordinate => coordinate.toFixed(8).replace(/\.?0+$/, "")).join(",");
}

function parseRoute(value) {
    const geometry = value?.geometry;
    if (geometry?.type !== "LineString" || !Array.isArray(geometry.coordinates) ||
        geometry.coordinates.length < 2 || geometry.coordinates.length > 16000 ||
        !geometry.coordinates.every(validPosition) ||
        !Number.isFinite(value.distance_m) || value.distance_m < 0 ||
        !Number.isFinite(value.duration_s) || value.duration_s < 0) {
        throw new Error("route_provider_error");
    }
    return value;
}

function addRouteLayers(map) {
    map.addSource(sourceId, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    const line = {
        source: sourceId,
        type: "line",
        filter: ["==", ["get", "kind"], "route"],
        layout: { "line-cap": "round", "line-join": "round" },
    };
    // Offset at street zoom so the route and the measured road colors remain visible together.
    const offset = ["interpolate", ["linear"], ["zoom"], 11, 0, 14, 8];
    map.addLayer({
        ...line,
        id: `${sourceId}-halo`,
        paint: { "line-color": "#ffffff", "line-width": 8, "line-offset": offset },
    });
    map.addLayer({
        ...line,
        id: `${sourceId}-line`,
        paint: { "line-color": "#5033a3", "line-width": 5, "line-offset": offset },
    });
    map.addLayer({
        id: `${sourceId}-points`,
        source: sourceId,
        type: "circle",
        filter: ["==", ["get", "kind"], "point"],
        paint: { "circle-radius": 10, "circle-color": "#5033a3", "circle-stroke-color": "#fff", "circle-stroke-width": 2 },
    });
    map.addLayer({
        id: `${sourceId}-point-labels`,
        source: sourceId,
        type: "symbol",
        filter: ["==", ["get", "kind"], "point"],
        layout: {
            "text-field": ["get", "label"], "text-font": ["Noto Sans Regular"], "text-size": 12,
            "text-allow-overlap": true, "text-ignore-placement": true,
        },
        paint: { "text-color": "#fff" },
    });
}

function fitRoute(map, coordinates) {
    let [west, south] = coordinates[0];
    let east = west;
    let north = south;
    for (const [lon, lat] of coordinates) {
        west = Math.min(west, lon);
        east = Math.max(east, lon);
        south = Math.min(south, lat);
        north = Math.max(north, lat);
    }
    if (east - west > 180) return;
    const bounds = map.getBounds();
    const corners = [[west, south], [east, north]];
    if (!corners.every(point => bounds.contains(point))) {
        map.fitBounds(corners, { padding: 34, maxZoom: 15, duration: 0 });
    }
}

/**
 * Mount once after the existing MapLibre style has loaded. The host owns the shared map click
 * and cursor handlers. Call handleMapClick(event) before inspecting observations and use
 * isPicking() to give the crosshair priority on mousemove. Pass an empty token when locking.
 */
export function createRoutePlanner({ root, map, onUnauthorized }) {
    root.classList.add("route-planner");
    root.innerHTML = `
        <h3 data-route-text="title"></h3>
        <p class="route-planner-help" id="route-planner-coordinate-help" data-route-text="help"></p>
        <p class="route-planner-help route-planner-privacy" hidden data-route-text="privacy"></p>
        <form autocomplete="off">
            <label for="route-planner-from">A · <span data-route-text="from"></span></label>
            <input type="text" id="route-planner-from" maxlength="32" spellcheck="false"
                aria-describedby="route-planner-coordinate-help" placeholder="-46.87, -23.51">
            <button type="button" data-route-pick="from" data-route-text="pickFrom" aria-pressed="false"></button>
            <label for="route-planner-to">B · <span data-route-text="to"></span></label>
            <input type="text" id="route-planner-to" maxlength="32" spellcheck="false"
                aria-describedby="route-planner-coordinate-help" placeholder="-46.86, -23.51">
            <button type="button" data-route-pick="to" data-route-text="pickTo" aria-pressed="false"></button>
            <div class="route-planner-actions">
                <button class="primary" type="submit"></button>
                <button type="button" class="route-planner-clear" data-route-text="clear"></button>
            </div>
        </form>
        <p class="route-planner-status" role="status" aria-live="polite" aria-atomic="true"></p>
        <p class="route-planner-credit" hidden>
            <a href="https://project-osrm.org/" target="_blank" rel="noopener noreferrer">OSRM</a> ·
            © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer" data-route-text="osmCredit"></a> ·
            <a href="https://opendatacommons.org/licenses/odbl/1-0/" target="_blank" rel="noopener noreferrer">ODbL</a> ·
            <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noopener noreferrer" data-route-text="fixMap"></a>
        </p>`;
    const form = root.querySelector("form");
    const inputs = Object.fromEntries(sides.map(side => [side, root.querySelector(`#route-planner-${side}`)]));
    const pickButtons = Object.fromEntries(sides.map(side => [side, root.querySelector(`[data-route-pick="${side}"]`)]));
    const submit = root.querySelector('button[type="submit"]');
    const clearButton = root.querySelector(".route-planner-clear");
    const status = root.querySelector(".route-planner-status");
    const credit = root.querySelector(".route-planner-credit");
    const privacy = root.querySelector(".route-planner-privacy");
    const labels = root.querySelectorAll("[data-route-text]");
    addRouteLayers(map);

    let token = "";
    let language = "pt-BR";
    let picking = null;
    let route = null;
    let config = null;
    let configRequest = null;
    let routeRequest = null;
    let notice = null;
    let hasError = false;

    function summary() {
        const unit = (value, unit, maximumFractionDigits = 0) =>
            new Intl.NumberFormat(language, { style: "unit", unit, maximumFractionDigits }).format(value);
        const distance = route.distance_m < 1000 ? unit(route.distance_m, "meter") :
            unit(route.distance_m / 1000, "kilometer", 2);
        let duration = unit(route.duration_s, "second");
        if (route.duration_s >= 60) {
            const minutes = Math.round(route.duration_s / 60);
            const remainder = minutes % 60;
            duration = minutes < 60 ? unit(minutes, "minute") : unit(Math.floor(minutes / 60), "hour");
            if (minutes >= 60 && remainder) duration += ` ${unit(remainder, "minute")}`;
        }
        return copy[language].result(distance, duration);
    }

    function render() {
        const text = copy[language];
        root.hidden = !token;
        credit.hidden = !config?.enabled;
        privacy.hidden = !config?.external;
        for (const side of sides) {
            inputs[side].disabled = !config?.enabled;
            const button = pickButtons[side];
            button.disabled = !config?.enabled;
            button.setAttribute("aria-pressed", String(picking === side));
        }
        submit.disabled = !!configRequest || config?.enabled === false || !!routeRequest;
        submit.textContent = !config && !configRequest ? text.retry : text.build;
        clearButton.disabled = !picking && sides.every(side => !inputs[side].value);
        status.dataset.error = String(hasError && !picking);
        const instruction = picking === "from" ? text.selectingFrom : text.selectingTo;
        const message = picking ? instruction : route ? summary() : (text[notice] ?? "");
        if (status.textContent !== message) status.textContent = message;
    }

    function showOverlay() {
        const features = [];
        if (route) features.push({ type: "Feature", geometry: route.geometry, properties: { kind: "route" } });
        for (const [index, side] of sides.entries()) {
            const coordinates = parsePoint(inputs[side].value);
            if (!coordinates) continue;
            features.push({
                type: "Feature", geometry: { type: "Point", coordinates },
                properties: { kind: "point", label: index === 0 ? "A" : "B" },
            });
        }
        map.getSource(sourceId).setData({ type: "FeatureCollection", features });
    }

    function resetRoute() {
        routeRequest?.abort();
        routeRequest = null;
        route = null;
        notice = null;
        hasError = false;
    }

    function clear() {
        resetRoute();
        picking = null;
        for (const side of sides) {
            inputs[side].value = "";
            inputs[side].removeAttribute("aria-invalid");
        }
        showOverlay();
        render();
    }

    function request(url, signal) {
        return fetch(url, {
            headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
            credentials: "omit",
            cache: "no-store",
            redirect: "error",
            signal,
        });
    }

    function denied(response) {
        if (response.status !== 401 && response.status !== 403) return false;
        setToken("");
        onUnauthorized?.();
        return true;
    }

    async function loadConfig() {
        const controller = new AbortController();
        configRequest = controller;
        notice = "configuring";
        hasError = false;
        render();
        try {
            const response = await request(`${routeUrl}/config`, controller.signal);
            if (configRequest !== controller || denied(response)) return;
            if (!response.ok) throw new Error("configError");
            const value = await response.json();
            if (configRequest !== controller) return;
            if (typeof value?.enabled !== "boolean" || typeof value.external !== "boolean") throw new Error("configError");
            config = value;
            notice = value.enabled ? null : "routing_disabled";
        } catch {
            if (configRequest !== controller) return;
            notice = "configError";
            hasError = true;
        } finally {
            if (configRequest === controller) {
                configRequest = null;
                render();
            }
        }
    }

    async function lookup() {
        picking = null;
        resetRoute();
        showOverlay();
        const from = parsePoint(inputs.from.value);
        const to = parsePoint(inputs.to.value);
        const same = from && to && encodePoint(from) === encodePoint(to);
        if (!from || !to || same) {
            inputs.from.setAttribute("aria-invalid", String(!from || !!same));
            inputs.to.setAttribute("aria-invalid", String(!to || !!same));
            (from && !same ? inputs.to : inputs.from).focus();
            notice = "invalid";
            hasError = true;
            render();
            return;
        }
        const controller = new AbortController();
        routeRequest = controller;
        notice = "loading";
        render();
        try {
            const params = new URLSearchParams({ from: encodePoint(from), to: encodePoint(to) });
            const response = await request(`${routeUrl}?${params}`, controller.signal);
            if (routeRequest !== controller || denied(response)) return;
            const value = await response.json();
            if (routeRequest !== controller) return;
            if (!response.ok) throw new Error(value?.error);
            route = parseRoute(value);
            showOverlay();
            fitRoute(map, [from, ...route.geometry.coordinates, to]);
        } catch (error) {
            if (routeRequest !== controller) return;
            notice = error.message === "invalid_route_points" ? "invalid" :
                errorCodes.has(error.message) ? error.message : "route_provider_error";
            hasError = true;
        } finally {
            if (routeRequest === controller) {
                routeRequest = null;
                render();
            }
        }
    }

    function setToken(value) {
        const next = value || "";
        if (next === token) return;
        configRequest?.abort();
        configRequest = null;
        config = null;
        token = next;
        clear();
        if (token) void loadConfig();
    }

    function setLanguage(value) {
        language = value === "en" ? "en" : "pt-BR";
        for (const label of labels) label.textContent = copy[language][label.dataset.routeText];
        render();
    }

    function isPicking() {
        return !!picking && !!token && !root.closest("[hidden]");
    }

    function handleMapClick(event) {
        if (!isPicking()) return false;
        const point = event.lngLat.wrap().toArray();
        if (validPosition(point)) {
            inputs[picking].value = encodePoint(point).replace(",", ", ");
            inputs[picking].removeAttribute("aria-invalid");
            resetRoute();
            showOverlay();
        }
        picking = null;
        render();
        return true;
    }

    form.addEventListener("submit", event => {
        event.preventDefault();
        if (!token || configRequest || routeRequest) return;
        if (!config) void loadConfig();
        else if (config.enabled) void lookup();
    });
    clearButton.addEventListener("click", clear);
    for (const side of sides) {
        pickButtons[side].addEventListener("click", () => {
            picking = picking === side ? null : side;
            render();
        });
        inputs[side].addEventListener("input", () => {
            inputs[side].removeAttribute("aria-invalid");
            picking = null;
            resetRoute();
            showOverlay();
            render();
        });
    }
    function cancelPicking(event) {
        if (event.key !== "Escape" || !isPicking()) return;
        picking = null;
        render();
    }
    root.addEventListener("keydown", cancelPicking);
    map.getCanvas().addEventListener("keydown", cancelPicking);
    setLanguage(language);
    return { setToken, setLanguage, isPicking, handleMapClick };
}
