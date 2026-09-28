/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

/*
 * Makes every rendered Mermaid diagram clickable: a click (or Enter/Space on the focused diagram)
 * opens it in a full-screen dialog that supports zooming via the mouse wheel or the toolbar and
 * panning by dragging. No external library is used.
 *
 * The Material theme renders a diagram into a "div.mermaid" host whose SVG lives in a *closed*
 * shadow root, so the SVG can neither be queried nor cloned from here. The dialog therefore moves
 * the host element itself into the overlay and puts it back on close, rather than copying it.
 */
(function () {
    "use strict";

    var MIN_SCALE = 0.2;
    var MAX_SCALE = 12;
    var ZOOM_STEP = 1.25;
    var FIT_MARGIN = 0.9;

    var TEXTS = {
        en: {
            open: "Click to enlarge",
            close: "Close",
            zoomIn: "Zoom in",
            zoomOut: "Zoom out",
            reset: "Fit to screen",
            dialog: "Enlarged diagram",
            hint: "Mouse wheel or +/- to zoom, drag to pan, Esc to close"
        },
        de: {
            open: "Zum Vergrößern klicken",
            close: "Schließen",
            zoomIn: "Vergrößern",
            zoomOut: "Verkleinern",
            reset: "An Bildschirm anpassen",
            dialog: "Vergrößertes Diagramm",
            hint: "Mausrad oder +/- zum Zoomen, Ziehen zum Verschieben, Esc zum Schließen"
        }
    };

    function texts() {
        var lang = (document.documentElement.getAttribute("lang") || "en").slice(0, 2).toLowerCase();
        return TEXTS[lang] || TEXTS.en;
    }

    var overlay = null;
    var stage = null;
    var canvas = null;
    var openWrapper = null;
    var lastFocused = null;
    var scale = 1;
    var fitScale = 1;
    var offsetX = 0;
    var offsetY = 0;
    var dragging = false;
    var dragStartX = 0;
    var dragStartY = 0;

    function applyTransform() {
        canvas.style.transform = "translate(" + offsetX + "px, " + offsetY + "px) scale(" + scale + ")";
    }

    function setScale(next, originX, originY) {
        var clamped = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next));
        if (clamped === scale) {
            return;
        }
        if (typeof originX === "number" && typeof originY === "number") {
            var rect = stage.getBoundingClientRect();
            var px = originX - rect.left - rect.width / 2;
            var py = originY - rect.top - rect.height / 2;
            var ratio = clamped / scale;
            offsetX = px - (px - offsetX) * ratio;
            offsetY = py - (py - offsetY) * ratio;
        }
        scale = clamped;
        applyTransform();
    }

    /* Scales the diagram so it fills as much of the dialog as it can without being cut off. */
    function fitToStage() {
        canvas.style.transform = "none";
        var content = canvas.getBoundingClientRect();
        var area = stage.getBoundingClientRect();
        if (content.width > 0 && content.height > 0) {
            fitScale = Math.min(
                (area.width * FIT_MARGIN) / content.width,
                (area.height * FIT_MARGIN) / content.height
            );
            fitScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, fitScale));
        } else {
            fitScale = 1;
        }
        scale = fitScale;
        offsetX = 0;
        offsetY = 0;
        applyTransform();
    }

    /*
     * Drawn as an SVG rather than a glyph: the Unicode arrows (U+2922 and friends) are rendered by
     * whichever system font steps in, and most of them place the arrow heads across the stroke
     * instead of at its ends.
     */
    var ICON_FIT = "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z";

    function icon(path) {
        var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        svg.setAttribute("focusable", "false");

        var shape = document.createElementNS("http://www.w3.org/2000/svg", "path");
        shape.setAttribute("d", path);
        shape.setAttribute("fill", "currentColor");

        svg.appendChild(shape);
        return svg;
    }

    function button(label, symbol, onClick) {
        var el = document.createElement("button");
        el.type = "button";
        el.className = "mermaid-zoom__button";
        el.setAttribute("aria-label", label);
        el.setAttribute("title", label);
        if (typeof symbol === "string") {
            el.textContent = symbol;
        } else {
            el.appendChild(symbol);
        }
        el.addEventListener("click", function (event) {
            event.stopPropagation();
            onClick();
        });
        return el;
    }

    function buildOverlay() {
        var t = texts();

        overlay = document.createElement("div");
        overlay.className = "mermaid-zoom";
        overlay.setAttribute("role", "dialog");
        overlay.setAttribute("aria-modal", "true");
        overlay.setAttribute("aria-label", t.dialog);
        overlay.hidden = true;

        var toolbar = document.createElement("div");
        toolbar.className = "mermaid-zoom__toolbar";
        toolbar.appendChild(button(t.zoomOut, "−", function () {
            setScale(scale / ZOOM_STEP);
        }));
        toolbar.appendChild(button(t.reset, icon(ICON_FIT), fitToStage));
        toolbar.appendChild(button(t.zoomIn, "+", function () {
            setScale(scale * ZOOM_STEP);
        }));
        toolbar.appendChild(button(t.close, "✕", close));

        stage = document.createElement("div");
        stage.className = "mermaid-zoom__stage";

        canvas = document.createElement("div");
        canvas.className = "mermaid-zoom__canvas";
        stage.appendChild(canvas);

        var hint = document.createElement("p");
        hint.className = "mermaid-zoom__hint";
        hint.textContent = t.hint;

        overlay.appendChild(toolbar);
        overlay.appendChild(stage);
        overlay.appendChild(hint);
        document.body.appendChild(overlay);

        overlay.addEventListener("click", function (event) {
            if (event.target === overlay || event.target === stage) {
                close();
            }
        });

        stage.addEventListener("wheel", function (event) {
            event.preventDefault();
            setScale(scale * (event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP), event.clientX, event.clientY);
        }, { passive: false });

        stage.addEventListener("dblclick", function (event) {
            event.preventDefault();
            fitToStage();
        });

        stage.addEventListener("pointerdown", function (event) {
            dragging = true;
            dragStartX = event.clientX - offsetX;
            dragStartY = event.clientY - offsetY;
            stage.setPointerCapture(event.pointerId);
            stage.classList.add("mermaid-zoom__stage--dragging");
        });

        stage.addEventListener("pointermove", function (event) {
            if (!dragging) {
                return;
            }
            offsetX = event.clientX - dragStartX;
            offsetY = event.clientY - dragStartY;
            applyTransform();
        });

        function endDrag(event) {
            if (!dragging) {
                return;
            }
            dragging = false;
            stage.classList.remove("mermaid-zoom__stage--dragging");
            if (stage.hasPointerCapture(event.pointerId)) {
                stage.releasePointerCapture(event.pointerId);
            }
        }

        stage.addEventListener("pointerup", endDrag);
        stage.addEventListener("pointercancel", endDrag);

        window.addEventListener("resize", function () {
            if (!overlay.hidden) {
                fitToStage();
            }
        });

        document.addEventListener("keydown", function (event) {
            if (overlay.hidden) {
                return;
            }
            if (event.key === "Escape") {
                close();
            } else if (event.key === "+" || event.key === "=") {
                setScale(scale * ZOOM_STEP);
            } else if (event.key === "-") {
                setScale(scale / ZOOM_STEP);
            } else if (event.key === "0") {
                fitToStage();
            }
        });
    }

    function open(wrapper) {
        if (!overlay) {
            buildOverlay();
        }
        if (openWrapper) {
            return;
        }

        var host = wrapper.querySelector(".mermaid");
        if (!host) {
            return;
        }

        /* Hold the page layout while the diagram is away from its place in the document. */
        wrapper.style.minHeight = wrapper.getBoundingClientRect().height + "px";

        openWrapper = wrapper;
        lastFocused = document.activeElement;

        canvas.appendChild(host);
        overlay.hidden = false;
        document.body.classList.add("mermaid-zoom-open");
        fitToStage();
        overlay.querySelector(".mermaid-zoom__button").focus();
    }

    function close() {
        if (!overlay || overlay.hidden) {
            return;
        }

        var host = canvas.querySelector(".mermaid");
        if (host && openWrapper) {
            openWrapper.appendChild(host);
            openWrapper.style.minHeight = "";
        }

        canvas.style.transform = "none";
        overlay.hidden = true;
        document.body.classList.remove("mermaid-zoom-open");
        openWrapper = null;

        if (lastFocused && typeof lastFocused.focus === "function") {
            lastFocused.focus();
        }
    }

    /*
     * The badge and the focus ring cannot sit on the host element itself - an element with a shadow
     * root renders neither its light children nor its own ::after - so each host is wrapped.
     */
    function wrap(host) {
        var parent = host.parentNode;
        if (!parent || (parent.classList && parent.classList.contains("mermaid-zoomable"))) {
            return;
        }

        var t = texts();
        var wrapper = document.createElement("div");
        wrapper.className = "mermaid-zoomable";
        wrapper.setAttribute("role", "button");
        wrapper.setAttribute("tabindex", "0");
        wrapper.setAttribute("aria-label", t.open);
        wrapper.setAttribute("title", t.open);

        parent.insertBefore(wrapper, host);
        wrapper.appendChild(host);
    }

    function wrapAll() {
        /* Only the rendered host is worth opening; an unrendered "pre.mermaid" is still source text. */
        var hosts = document.querySelectorAll("div.mermaid");
        Array.prototype.forEach.call(hosts, wrap);
    }

    function zoomableOf(node) {
        return node && typeof node.closest === "function" ? node.closest(".mermaid-zoomable") : null;
    }

    document.addEventListener("click", function (event) {
        if (overlay && !overlay.hidden) {
            return;
        }
        var wrapper = zoomableOf(event.target);
        if (wrapper) {
            open(wrapper);
        }
    });

    document.addEventListener("keydown", function (event) {
        if (event.key !== "Enter" && event.key !== " ") {
            return;
        }
        if (overlay && !overlay.hidden) {
            return;
        }
        var wrapper = zoomableOf(event.target);
        if (wrapper) {
            event.preventDefault();
            open(wrapper);
        }
    });

    /*
     * Mermaid renders asynchronously, and the Material theme re-renders every diagram when the
     * colour scheme is toggled - so hosts are picked up through an observer rather than a single
     * pass after DOMContentLoaded.
     */
    var scheduled = false;
    var observer = new MutationObserver(function () {
        if (scheduled) {
            return;
        }
        scheduled = true;
        window.requestAnimationFrame(function () {
            scheduled = false;
            wrapAll();
        });
    });

    function start() {
        wrapAll();
        observer.observe(document.body, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
})();
