const pageSize = 20;
const viewLevels = { cities: "city", states: "state", countries: "country" };
const levels = ["street", "city", "state", "country"];
const metrics = [
    "segment_count", "observed_km", "average_speed_kmh", "potholes", "speed_bumps", "average_penalty_points",
];

const messages = {
    "pt-BR": {
        cities: "Estatísticas por cidade", states: "Estatísticas por estado",
        countries: "Estatísticas por país", rankings: "Ranking de qualidade das vias",
        coverage: "Cobertura das medições",
        coverageNote: "A distância soma as passagens medidas, inclusive as repetidas no mesmo trecho.",
        place: "Local", rank: "Posição", level: "Agrupar por", sort: "Ordenar por",
        street: "Rua", city: "Cidade", state: "Estado", country: "País",
        best: "Melhores (menor penalidade média)", worst: "Piores (maior penalidade média)",
        segment_count: "Trechos medidos", observed_km: "Distância medida (km)",
        average_speed_kmh: "Velocidade média (km/h)", potholes: "Buracos",
        speed_bumps: "Lombadas", average_penalty_points: "Penalidade média (pontos)",
        notMeasured: "Sem medição", loading: "Carregando estatísticas…",
        locked: "Informe um token para consultar as estatísticas.",
        denied: "Token inválido ou sem permissão de leitura. Informe outro token.",
        malformed: "O serviço retornou estatísticas em um formato inesperado.",
        requestError: "Não foi possível carregar as estatísticas. Tente novamente.",
        empty: "Nenhuma medição disponível.", emptyPage: "Nenhum resultado nesta página.",
        pagination: "Páginas de resultados", previous: "Anterior", next: "Próxima",
        range: (first, last, total) => `${first}–${last} de ${total}`,
    },
    en: {
        cities: "City statistics", states: "State statistics",
        countries: "Country statistics", rankings: "Road quality rankings",
        coverage: "Measurement coverage",
        coverageNote: "Distance adds up all measured passages, including repeat trips over the same segment.",
        place: "Place", rank: "Rank", level: "Group by", sort: "Sort by",
        street: "Street", city: "City", state: "State", country: "Country",
        best: "Best (lowest average penalty)", worst: "Worst (highest average penalty)",
        segment_count: "Measured segments", observed_km: "Measured distance (km)",
        average_speed_kmh: "Average speed (km/h)", potholes: "Potholes",
        speed_bumps: "Speed bumps", average_penalty_points: "Average penalty (points)",
        notMeasured: "Not measured", loading: "Loading statistics…",
        locked: "Enter a token to view statistics.",
        denied: "Token is invalid or lacks read permission. Enter another token.",
        malformed: "The statistics service returned data in an unexpected format.",
        requestError: "Could not load statistics. Try again.",
        empty: "No measurements available.", emptyPage: "No results on this page.",
        pagination: "Result pages", previous: "Previous", next: "Next",
        range: (first, last, total) => `${first}–${last} of ${total}`,
    },
};

export function createStatistics(root, { onUnauthorized } = {}) {
    const doc = root.ownerDocument;
    const element = (tag, className) => {
        const node = doc.createElement(tag);
        if (className) node.className = className;
        return node;
    };
    const title = element("h2");
    const note = element("p", "stats-note");
    const controls = element("div", "stats-controls");
    const toolbar = element("div", "stats-toolbar");
    const status = element("p", "stats-status");
    const pages = element("nav", "stats-pages");
    const previous = element("button");
    const next = element("button");
    const results = element("div", "stats-results");
    const labels = [];

    function label(node, key) {
        labels.push([node, key]);
        return node;
    }

    function select(key, keys) {
        const field = element("label");
        const input = element("select");
        for (const value of keys) {
            const option = label(element("option"), value);
            option.value = value;
            input.append(option);
        }
        field.append(label(element("span"), key), input);
        controls.append(field);
        return input;
    }

    const levelSelect = select("level", levels);
    const sortSelect = select("sort", ["best", "worst"]);
    status.setAttribute("role", "status");
    status.setAttribute("aria-live", "polite");
    status.setAttribute("aria-atomic", "true");
    results.setAttribute("role", "region");
    results.tabIndex = 0;
    previous.type = next.type = "button";
    pages.append(label(previous, "previous"), label(next, "next"));
    toolbar.append(status, pages);
    root.classList.add("stats-panel");
    root.replaceChildren(title, label(note, "coverageNote"), controls, toolbar, results);
    root.hidden = true;

    let language = "pt-BR";
    let view;
    let token = "";
    let offset = 0;
    let pending;
    let page;
    let state = "locked";
    const t = key => messages[language][key];
    const level = () => view === "rankings" ? levelSelect.value : viewLevels[view];

    function renderTable(number) {
        const ranking = view === "rankings";
        const table = element("table");
        const caption = element("caption");
        caption.textContent = t(ranking ? "rankings" : "coverage");
        results.setAttribute("aria-label", caption.textContent);
        const head = element("thead");
        const headings = element("tr");
        for (const key of [...(ranking ? ["rank"] : []), "place", ...metrics]) {
            const th = element("th");
            th.scope = "col";
            th.textContent = t(key);
            headings.append(th);
        }
        head.append(headings);
        const body = element("tbody");
        const currentLevel = level();
        const parents = levels.slice(levels.indexOf(currentLevel) + 1);
        for (const [index, item] of page.items.entries()) {
            const row = element("tr");
            if (ranking) {
                const rank = element("td", "stats-number");
                rank.textContent = number.format(offset + index + 1);
                row.append(rank);
            }
            const name = element("th", "stats-place");
            name.scope = "row";
            name.textContent = item.name;
            const places = parents.map(key => item[key]).filter(value => typeof value === "string" && value);
            if (places.length) {
                const location = element("span", "stats-location");
                location.textContent = places.join(", ");
                name.append(location);
            }
            row.append(name);
            for (const key of metrics) {
                const cell = element("td", "stats-number");
                const measured = Number.isFinite(item[key]);
                cell.textContent = measured ? number.format(item[key]) : t("notMeasured");
                cell.classList.toggle("stats-unmeasured", !measured);
                row.append(cell);
            }
            body.append(row);
        }
        table.append(caption, head, body);
        results.replaceChildren(table);
    }

    function render() {
        const number = new Intl.NumberFormat(language, { maximumFractionDigits: 2 });
        const count = page?.items.length ?? 0;
        const hasPrevious = offset > 0;
        const hasNext = count > 0 && offset + pageSize < page.total;
        title.textContent = view ? t(view) : "";
        controls.hidden = view !== "rankings";
        pages.setAttribute("aria-label", t("pagination"));
        if (state !== "loading") pages.hidden = !hasPrevious && !hasNext;
        previous.setAttribute("aria-disabled", String(!hasPrevious || state === "loading"));
        next.setAttribute("aria-disabled", String(!hasNext || state === "loading"));
        status.dataset.error = String(["denied", "malformed", "requestError"].includes(state));
        status.textContent = state === "loaded" ? t("range")(
            number.format(offset + 1), number.format(offset + count), number.format(page.total),
        ) : t(state);
        results.hidden = !count;
        if (count) renderTable(number);
        else results.replaceChildren();
    }

    function cancelRequest() {
        pending?.abort();
        pending = undefined;
    }

    async function load() {
        cancelRequest();
        page = undefined;
        state = token ? "loading" : "locked";
        render();
        if (!token) return;

        const request = pending = new AbortController();
        const isCurrent = () => pending === request && !request.signal.aborted;
        const params = new URLSearchParams({
            level: level(),
            sort: view === "rankings" ? sortSelect.value : "coverage",
            limit: pageSize,
            offset,
        });
        let unauthorized = false;
        try {
            const response = await fetch(`/v1/map/statistics?${params}`, {
                headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
                cache: "no-store",
                credentials: "omit",
                redirect: "error",
                signal: request.signal,
            });
            if (!isCurrent()) return;
            if (response.status === 401 || response.status === 403) {
                state = "denied";
                unauthorized = true;
            } else if (!response.ok) {
                state = "requestError";
            } else {
                const data = await response.json().catch(() => null);
                if (!isCurrent()) return;
                if (!Array.isArray(data?.items) || !Number.isSafeInteger(data.total) || data.total < 0 ||
                    !data.items.every(item => typeof item?.name === "string")) {
                    state = "malformed";
                } else {
                    page = data;
                    state = data.items.length ? "loaded" : data.total ? "emptyPage" : "empty";
                }
            }
        } catch (error) {
            if (!isCurrent() || error.name === "AbortError") return;
            state = "requestError";
        } finally {
            if (isCurrent()) pending = undefined;
        }
        render();
        if (unauthorized) onUnauthorized?.();
    }

    function changePage(button, step) {
        if (button.getAttribute("aria-disabled") === "true") return;
        offset += step * pageSize;
        load();
    }

    for (const control of [levelSelect, sortSelect]) {
        control.addEventListener("change", () => {
            if (view !== "rankings") return;
            offset = 0;
            load();
        });
    }
    previous.addEventListener("click", () => changePage(previous, -1));
    next.addEventListener("click", () => changePage(next, 1));

    function setLanguage(value) {
        language = value === "en" ? "en" : "pt-BR";
        root.lang = language;
        for (const [node, key] of labels) node.textContent = t(key);
        render();
    }

    function show(nextView, nextToken) {
        if (nextView !== "rankings" && !Object.hasOwn(viewLevels, nextView)) {
            hide();
            return;
        }
        const value = nextToken ?? "";
        if (view !== nextView || token !== value) offset = 0;
        view = nextView;
        token = value;
        root.hidden = false;
        return load();
    }

    function hide() {
        cancelRequest();
        root.hidden = true;
        view = undefined;
        token = "";
        offset = 0;
        page = undefined;
        state = "locked";
        render();
    }

    setLanguage(language);
    return { show, setLanguage, hide };
}
