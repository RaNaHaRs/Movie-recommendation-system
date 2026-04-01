document.documentElement.classList.add("js");

document.addEventListener("DOMContentLoaded", () => {
    document.body.classList.add("is-ready");

    const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const revealObserver = createRevealObserver(prefersReducedMotion);

    registerRevealItems(document, revealObserver);
    bindPasswordToggles(document);
    bindSearchForm(prefersReducedMotion, revealObserver);
    bindTransitionLinks(prefersReducedMotion);
});

function createRevealObserver(prefersReducedMotion) {
    if (prefersReducedMotion || !("IntersectionObserver" in window)) {
        return null;
    }

    return new IntersectionObserver(
        (entries, observer) => {
            entries.forEach((entry) => {
                if (!entry.isIntersecting) {
                    return;
                }

                entry.target.classList.add("is-visible");
                observer.unobserve(entry.target);
            });
        },
        {
            threshold: 0.18,
            rootMargin: "0px 0px -8% 0px"
        }
    );
}

function registerRevealItems(root, revealObserver) {
    root.querySelectorAll("[data-reveal]").forEach((item, index) => {
        if (item.dataset.revealReady === "true") {
            return;
        }

        item.dataset.revealReady = "true";
        item.style.setProperty("--reveal-delay", `${index * 70}ms`);

        if (revealObserver) {
            revealObserver.observe(item);
        } else {
            item.classList.add("is-visible");
        }
    });
}

function bindPasswordToggles(root) {
    root.querySelectorAll("[data-password-toggle]").forEach((button) => {
        if (button.dataset.passwordBound === "true") {
            return;
        }

        button.dataset.passwordBound = "true";
        const targetId = button.getAttribute("aria-controls");
        if (!targetId) {
            return;
        }

        const input = document.getElementById(targetId);
        if (!input) {
            return;
        }

        button.addEventListener("click", () => {
            const isVisible = input.type === "text";
            input.type = isVisible ? "password" : "text";
            button.classList.toggle("is-visible", !isVisible);
            button.setAttribute("aria-label", isVisible ? "Show password" : "Hide password");
        });
    });
}

function bindSearchForm(prefersReducedMotion, revealObserver) {
    const searchForm = document.querySelector("[data-search-form]");
    const searchInput = document.querySelector("[data-search-input]");
    let searchRegion = document.querySelector("[data-search-results-region]");

    if (!searchForm || !searchInput) {
        return;
    }

    let debounceId = 0;
    let lastSubmitted = searchInput.value.trim();
    let activeRequestId = 0;

    const submitWithAjax = async () => {
        if (!searchRegion || !window.fetch) {
            searchForm.submit();
            return;
        }

        const currentQuery = searchInput.value.trim();
        const url = new URL(searchForm.action, window.location.href);
        if (currentQuery) {
            url.searchParams.set("query", currentQuery);
        } else {
            url.searchParams.delete("query");
        }

        const requestId = ++activeRequestId;
        searchRegion.setAttribute("aria-busy", "true");
        document.body.classList.add("is-searching");

        try {
            const response = await fetch(url.toString(), {
                headers: {
                    "X-Requested-With": "XMLHttpRequest"
                }
            });

            if (!response.ok) {
                throw new Error(`Search request failed with status ${response.status}`);
            }

            const html = await response.text();
            if (requestId !== activeRequestId) {
                return;
            }

            const parser = new DOMParser();
            const documentFragment = parser.parseFromString(html, "text/html");
            const nextRegion = documentFragment.querySelector("[data-search-results-region]");
            if (!nextRegion) {
                throw new Error("Search results fragment was not found.");
            }

            searchRegion.replaceWith(nextRegion);
            searchRegion = nextRegion;
            registerRevealItems(document, revealObserver);
            window.history.replaceState({}, "", currentQuery ? url.pathname + url.search : "/");

            if (!prefersReducedMotion) {
                searchRegion.classList.add("is-visible");
            }
        } catch (error) {
            window.location.href = url.toString();
        } finally {
            if (searchRegion) {
                searchRegion.removeAttribute("aria-busy");
            }
            document.body.classList.remove("is-searching");
        }
    };

    searchForm.addEventListener("submit", (event) => {
        searchRegion = document.querySelector("[data-search-results-region]");
        if (!searchRegion || !window.fetch) {
            return;
        }

        event.preventDefault();
        lastSubmitted = searchInput.value.trim();
        submitWithAjax();
    });

    if (!searchRegion) {
        return;
    }

    searchInput.addEventListener("input", () => {
        window.clearTimeout(debounceId);
        debounceId = window.setTimeout(() => {
            const currentQuery = searchInput.value.trim();
            if (currentQuery === lastSubmitted) {
                return;
            }

            if (currentQuery.length === 0 || currentQuery.length >= 2) {
                lastSubmitted = currentQuery;
                submitWithAjax();
            }
        }, 500);
    });

    searchInput.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && searchInput.value.trim().length > 0) {
            searchInput.value = "";
            lastSubmitted = "";
            submitWithAjax();
        }
    });
}

function bindTransitionLinks(prefersReducedMotion) {
    document.addEventListener("click", (event) => {
        const link = event.target.closest("[data-transition-link]");
        if (!link) {
            return;
        }

        const href = link.getAttribute("href");
        if (!href || href.startsWith("#") || link.target === "_blank" || event.metaKey || event.ctrlKey) {
            return;
        }

        const url = new URL(link.href, window.location.href);
        if (url.origin !== window.location.origin || prefersReducedMotion) {
            return;
        }

        event.preventDefault();
        document.body.classList.add("is-leaving");
        window.setTimeout(() => {
            window.location.href = url.toString();
        }, 170);
    });
}
