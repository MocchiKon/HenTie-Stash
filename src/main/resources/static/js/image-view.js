// Chapter image viewer: navigation, view modes, prefetching, ComfyUI workflows, stepping into neighbour chapters.
(function () {
    "use strict";

    var data = window.IMAGE_VIEW || {};
    var urls = data.pageUrls || [];
    var body = document.body;
    var img = document.getElementById("page-img");
    var counter = document.getElementById("counter");
    var stage = document.getElementById("stage");
    var viewer = document.getElementById("viewer");
    var noImages = document.getElementById("no-images");
    var modeSelect = document.getElementById("mode-select");
    var pageInput = document.getElementById("page-input");
    var workflowSelect = document.getElementById("workflow-select");
    var workflowNote = document.getElementById("workflow-note");
    var statusBox = document.getElementById("process-status");
    var originalBtn = document.getElementById("original-btn");
    var notice = document.getElementById("chapter-notice");

    var index = Math.min(Math.max((data.startPage || 1) - 1, 0), Math.max(urls.length - 1, 0));

    // The ComfyUI workflow the pages are shown through; "" shows them as stored.
    var workflow = data.defaultWorkflow || "";

    // Showing the stored page although a workflow is selected. Processing goes on; turning the page ends it.
    var peeking = false;

    function setMode(mode) {
        body.classList.remove("mode-fit", "mode-fit-horizontal", "mode-original");
        body.classList.add("mode-" + mode);
        if (modeSelect) {
            modeSelect.value = mode.toUpperCase().replace(/-/g, "_");
        }
    }

    // `done` runs once: a page shown in its place must not start prefetching again.
    function show(src, done) {
        img.onload = img.onerror = function () {
            img.onload = img.onerror = null;
            if (done) { done(); }
        };
        img.src = src;
    }

    function render() {
        if (urls.length === 0) {
            return;
        }
        counter.textContent = (index + 1) + " / " + urls.length;
        if (pageInput) { pageInput.max = urls.length; }
        if (viewer) {
            viewer.scrollTop = 0;
            viewer.scrollLeft = 0;
        }
        var u = new URL(window.location.href);
        u.searchParams.set("page", index + 1);
        history.replaceState(null, "", u.toString());
        load();
    }

    // Prefetching waits for the page being read, so it never competes with it. The old position's token is
    // spent now, not when the new page arrives, so its chain starts nothing more meanwhile.
    function load() {
        abortRequests();
        var token = ++prefetchToken;
        var reading = index;
        hideStatus();
        hideNotice();
        peeking = false;
        updateOriginalButton();
        if (workflow) {
            renderProcessed(token, reading);
            return;
        }
        show(urls[reading], function () {
            prefetched[reading] = true;
            prefetch(token, reading);
        });
    }

    // --- Prefetching ---
    //     Strictly ONE request in flight, and the plan is dropped whenever the reader moves: with a workflow a
    //     page can be many seconds of server work, and a queue from an old position would sit in front of the
    //     page the reader jumped to. Do not turn this into a queue.
    var prefetched = {};
    var prefetchToken = 0;
    var PAGES_AHEAD = Math.max(0, data.pagesAhead | 0);
    var PREVIOUS_AFTER = 4;

    // Ordered by how likely each page is wanted: the next few, then the previous one, then the rest ahead.
    // 0 pages ahead prepares nothing, not even the previous page.
    function planFrom(from) {
        var order = [];
        var last = Math.min(urls.length - 1, from + PAGES_AHEAD);
        var beforePrevious = Math.min(last, from + PREVIOUS_AFTER);
        var i;
        for (i = from + 1; i <= beforePrevious; i++) { order.push(i); }
        if (PAGES_AHEAD > 0 && from > 0) { order.push(from - 1); }
        for (i = beforePrevious + 1; i <= last; i++) { order.push(i); }
        return order;
    }

    // `from` is passed in, not read from `index`, so a chain only plans around its own token's position.
    function prefetch(token, from) {
        var order = planFrom(from);
        var at = 0;
        (function next() {
            if (token !== prefetchToken) { return; }
            while (at < order.length && prefetched[order[at]]) { at++; }
            if (at >= order.length) { return; }
            var target = order[at++];
            var image = new Image();
            image.onload = image.onerror = function () {
                prefetched[target] = true;
                next();
            };
            image.src = urls[target];
        })();
    }

    // --- ComfyUI workflows ---
    //     Processed pages are fetched, not put in an <img>: an <img> cannot receive a 202 with progress or an
    //     error message. The stored page is shown meanwhile, so reading never waits on ComfyUI.
    //
    //     Every request names this viewer, its token and its plan; the server counts only a viewer's latest
    //     request and stops a page nobody wants any more. The plan tells "collecting ready pages on the way"
    //     apart from "moved on".
    //
    //     Processed pages can be tens of megabytes, so only those near the reader stay in memory; pages further
    //     ahead are only made ready on the server ("warm").
    //
    //     Stale requests are aborted: a browser keeps six connections per server, and a few open long polls
    //     would block the next page and even Back. The server cannot see an abort, hence leaveComfyUi.
    // The page on screen polls every second because each answer carries its progress line; a prefetch shows
    // nothing, so the server may hold its request until the page is ready.
    var LONG_POLL_SECONDS = 15;
    var PROGRESS_POLL_SECONDS = 1;
    var KEEP_PROCESSED = 6;
    var processed = {};
    var warmed = {};
    var inflight = [];

    // Not randomUUID: it needs a secure context, and the app is reached over plain HTTP on the LAN.
    var viewerId = Array.prototype.map.call(window.crypto.getRandomValues(new Uint8Array(8)), function (b) {
        return (b + 256).toString(16).substring(1);
    }).join("");

    var csrfParam = window.metaContent("csrf-param");
    var csrfToken = window.metaContent("csrf-token");

    function abortRequests() {
        inflight.forEach(function (controller) { controller.abort(); });
        inflight = [];
    }

    function pageName(i) {
        return urls[i].substring(urls[i].lastIndexOf("/") + 1);
    }

    function planNames(from) {
        return [from].concat(planFrom(from)).map(pageName);
    }

    function processUrl(i, forWorkflow, wait, options) {
        return data.processBase + "/" + encodeURIComponent(pageName(i))
            + "?workflow=" + encodeURIComponent(forWorkflow) + "&wait=" + wait
            + (options.prefetch ? "&prefetch=true" : "") + (options.warm ? "&warm=true" : "")
            + "&viewer=" + viewerId + "&seq=" + options.token
            + options.plan.map(function (name) { return "&plan=" + encodeURIComponent(name); }).join("");
    }

    // Resolves with an object URL (true for a warm request) or null once no longer wanted; rejects with the
    // server's message.
    function fetchProcessed(i, forWorkflow, options) {
        var controller = new AbortController();
        inflight.push(controller);
        var wanted = function () {
            return !controller.signal.aborted && options.token === prefetchToken && forWorkflow === workflow;
        };
        return new Promise(function (resolve, reject) {
            var wait = options.firstWait || 0;
            (function ask() {
                if (!wanted()) { resolve(null); return; }
                var asked = Date.now();
                fetch(processUrl(i, forWorkflow, wait, options), {
                    cache: "no-store", signal: controller.signal, headers: { Accept: "image/png, application/json" }
                }).then(function (r) {
                    if (r.status === 204) { resolve(true); return null; }
                    if (r.status === 200) {
                        return r.blob().then(function (b) { resolve(URL.createObjectURL(b)); });
                    }
                    return r.json().catch(function () { return {}; }).then(function (answer) {
                        // An answer about a page the reader has left says nothing about the page on screen.
                        if (!wanted()) { resolve(null); return; }
                        if (r.status !== 202) {
                            reject(new Error(answer.error || ("The server answered HTTP " + r.status + ".")));
                            return;
                        }
                        if (options.onStatus) { options.onStatus(answer); }
                        // After a request that did not wait, ask again at once; otherwise keep a second between
                        // requests so an early answer cannot become a busy loop.
                        var waited = wait > 0;
                        wait = options.pollWait || LONG_POLL_SECONDS;
                        window.setTimeout(ask, waited ? Math.max(0, 1000 - (Date.now() - asked)) : 0);
                    });
                }).catch(function () {
                    if (wanted()) { reject(new Error("The app did not answer.")); } else { resolve(null); }
                });
            })();
        }).finally(function () {
            var at = inflight.indexOf(controller);
            if (at >= 0) { inflight.splice(at, 1); }
        });
    }

    function renderProcessed(token, reading) {
        var plan = planNames(reading);
        if (processed[reading]) {
            show(processed[reading], function () { prefetchProcessed(token, reading, plan, true); });
            return;
        }
        var shown = false;
        var showStored = function () {
            if (!shown && token === prefetchToken) {
                shown = true;
                show(urls[reading]);
            }
        };
        // A cached result arrives at once; showing the stored page first would only make it flash.
        var fallback = window.setTimeout(showStored, 300);
        var forWorkflow = workflow;
        fetchProcessed(reading, forWorkflow, {
            token: token,
            plan: plan,
            pollWait: PROGRESS_POLL_SECONDS,
            onStatus: function (status) {
                showStored();
                showStatus(forWorkflow + " - " + (status.message || "Processing"), false);
            }
        }).then(function (objectUrl) {
            window.clearTimeout(fallback);
            if (!objectUrl) { return; }
            remember(reading, objectUrl, forWorkflow);
            if (token !== prefetchToken) { return; }
            hideStatus();
            shown = true;
            if (peeking) {
                // The stored page stays until the peek ends; prefetching need not wait for it.
                prefetchProcessed(token, reading, plan);
                return;
            }
            show(objectUrl, function () { prefetchProcessed(token, reading, plan); });
        }, function (err) {
            window.clearTimeout(fallback);
            if (token !== prefetchToken) { return; }
            showStored();
            showStatus(err.message + " Showing the page as stored.", true);
        });
    }

    // `fromMemory`: no request has told the server about this position yet.
    function prefetchProcessed(token, from, plan, fromMemory) {
        var order = planFrom(from);
        var forWorkflow = workflow;
        var at = 0;
        var asked = !fromMemory;
        (function next() {
            if (token !== prefetchToken || forWorkflow !== workflow) { return; }
            // Only the next page is kept in memory; the others are only warmed on the server.
            while (at < order.length && (processed[order[at]] || (at > 0 && warmed[order[at]]))) { at++; }
            if (at >= order.length) {
                // Nothing to fetch, but the server must still hear of the move, or it keeps running the old page.
                if (!asked) { tellPosition(token, from, forWorkflow, plan); }
                return;
            }
            asked = true;
            var keep = at === 0;
            var target = order[at++];
            fetchProcessed(target, forWorkflow, {
                token: token,
                plan: plan,
                prefetch: true,
                warm: !keep,
                firstWait: LONG_POLL_SECONDS
            }).then(function (result) {
                if (result === null) { return; }
                if (keep) { remember(target, result, forWorkflow); } else { warmed[target] = true; }
                next();
            }, function () {
                // Stop here; the reader sees the error on reaching that page.
            });
        })();
    }

    // A warm request only to tell the server the new position, so it drops what the plan no longer reaches.
    function tellPosition(token, i, forWorkflow, plan) {
        var controller = new AbortController();
        inflight.push(controller);
        fetch(processUrl(i, forWorkflow, 0, { token: token, plan: plan, warm: true, prefetch: true }),
            { cache: "no-store", signal: controller.signal })
            .catch(function () { /* the server never hears it; the page it runs goes on to its end */ })
            .finally(function () {
                var at = inflight.indexOf(controller);
                if (at >= 0) { inflight.splice(at, 1); }
            });
    }

    function remember(i, objectUrl, forWorkflow) {
        if (forWorkflow !== workflow) {
            URL.revokeObjectURL(objectUrl);
            return;
        }
        if (processed[i] && processed[i] !== objectUrl) { URL.revokeObjectURL(processed[i]); }
        processed[i] = objectUrl;
        // Keep the pages nearest the reader; the rest are in the server's cache.
        Object.keys(processed).map(Number)
            .sort(function (a, b) { return Math.abs(a - index) - Math.abs(b - index); })
            .slice(KEEP_PROCESSED)
            .forEach(function (k) {
                if (img.src !== processed[k]) {
                    URL.revokeObjectURL(processed[k]);
                    delete processed[k];
                }
            });
    }

    // The server cannot see an abort, so leaving says so explicitly. The token moves on first, so a request
    // still on its way counts as older than this one.
    function leaveComfyUi() {
        var token = ++prefetchToken;
        abortRequests();
        var form = new URLSearchParams();
        form.append("viewer", viewerId);
        form.append("seq", String(token));
        if (csrfParam) { form.append(csrfParam, csrfToken); }
        // A beacon is the one request a page being left can count on sending.
        if (!(navigator.sendBeacon && navigator.sendBeacon(data.leaveUrl, form))) {
            fetch(data.leaveUrl, { method: "POST", body: form, keepalive: true })
                .catch(function () { /* the server never hears it; the page runs to its end */ });
        }
    }

    function selectWorkflow(name) {
        if (name === workflow) { return; }
        // Stored pages ask the server nothing, so it would not learn otherwise that nothing is wanted.
        if (!name && urls.length) { leaveComfyUi(); }
        workflow = name;
        Object.keys(processed).forEach(function (k) { URL.revokeObjectURL(processed[k]); });
        processed = {};
        warmed = {};
        render();
    }

    // Only swaps the src: a pending onload (the start of prefetching) stays set and fires for the swapped page.
    function peek(on) {
        if (!workflow || urls.length === 0) { return; }
        peeking = on;
        updateOriginalButton();
        var src = !peeking && processed[index] ? processed[index] : urls[index];
        if (img.getAttribute("src") !== src) { img.src = src; }
    }

    function updateOriginalButton() {
        if (!originalBtn) { return; }
        originalBtn.disabled = !workflow || urls.length === 0;
        originalBtn.setAttribute("aria-pressed", peeking ? "true" : "false");
        originalBtn.title = originalBtn.disabled
            ? "Pick a workflow under the cogwheel to compare its pages with the pages as stored"
            : "Show this page as stored (s)";
    }

    function showStatus(text, isError) {
        if (!statusBox) { return; }
        statusBox.textContent = text;
        statusBox.classList.toggle("error", isError);
        statusBox.hidden = false;
    }

    function hideStatus() {
        if (statusBox) { statusBox.hidden = true; }
    }

    function note(text) {
        if (!workflowNote) { return; }
        workflowNote.textContent = text || "";
        workflowNote.hidden = !text;
    }

    // A workflow that cannot run is listed disabled: the viewer has no other place to say why it is missing.
    function loadWorkflows() {
        fetch(data.workflowsUrl, { headers: { Accept: "application/json" } })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (list) {
                if (!list || !workflowSelect) { return; }
                window.fillWorkflowSelect(workflowSelect, list.workflows, true);
                if (!list.reachable) {
                    note(list.starting ? "ComfyUI is starting..." : "ComfyUI is not answering.");
                    if (list.starting) { window.setTimeout(loadWorkflows, 3000); }
                } else {
                    note(list.problem);
                }
            })
            .catch(function () { /* the dropdown keeps what it has */ });
    }

    updateOriginalButton();
    if (urls.length === 0) {
        if (img) { img.hidden = true; }
        if (noImages) { noImages.hidden = false; }
        if (counter) { counter.textContent = "0 / 0"; }
    } else {
        setMode(data.defaultViewMode || "fit");
        render();
    }

    // Abort on beforeunload, before the browser asks for the next page, so leaving never waits for a
    // connection a ComfyUI poll holds. A page restored from the back-forward cache has nothing in flight.
    window.addEventListener("beforeunload", abortRequests);
    window.addEventListener("pagehide", function () {
        if (workflow && urls.length) { leaveComfyUi(); } else { abortRequests(); }
    });
    window.addEventListener("pageshow", function (e) {
        if (e.persisted && urls.length) { load(); }
    });

    document.getElementById("prev-btn").addEventListener("click", function () { go(-1); });
    document.getElementById("next-btn").addEventListener("click", function () { go(1); });
    if (originalBtn) {
        originalBtn.addEventListener("click", function () { peek(!peeking); });
    }

    // `repeat` (a held key) may make the first press past the end but never the second, so holding a key
    // through the last page does not leave the chapter.
    function go(delta, repeat) {
        var next = index + delta;
        if (next >= 0 && next < urls.length) {
            index = next;
            render();
        } else if (!repeat || !crossing || crossing.forward !== (delta > 0)) {
            pastChapterEnd(delta > 0);
        }
    }

    // --- Past either end of the chapter ---
    //     The first press asks the server for the neighbour (opening the viewer touches no database row, so it
    //     does not know it) and names it; the second opens it, the previous one at its last page. Two presses
    //     because the click zones cover the whole page: a tap meant for a still-loading last page must not
    //     leave the chapter.
    var crossing = null;

    function pastChapterEnd(forward) {
        if (crossing && crossing.forward === forward) {
            // undefined: still asking - go the moment the answer comes. null: there is nowhere to go.
            if (crossing.chapter) { window.location.href = chapterUrl(crossing.chapter, forward); }
            else if (crossing.chapter === undefined) { crossing.go = true; }
            return;
        }
        var asked = { forward: forward, chapter: undefined, go: false };
        crossing = asked;
        showNotice(forward ? "End of chapter." : "Start of chapter.");
        fetch(data.neighbourUrl + "?direction=" + (forward ? "next" : "previous"),
            { headers: { Accept: "application/json" } })
            .then(function (r) {
                if (r.status === 204) { return null; }
                if (!r.ok) { throw new Error("HTTP " + r.status); }
                return r.json();
            })
            .then(function (chapter) {
                if (crossing !== asked) { return; }
                asked.chapter = chapter;
                if (chapter && asked.go) {
                    window.location.href = chapterUrl(chapter, forward);
                } else if (chapter) {
                    showNotice((forward ? "End of chapter - press again for the next one: "
                        : "Start of chapter - press again for the previous one: ") + chapter.title);
                } else {
                    showNotice(forward ? "End of chapter - there is no next one."
                        : "Start of chapter - there is no previous one.");
                }
            })
            .catch(function () {
                if (crossing !== asked) { return; }
                crossing = null;   // the next press asks again
                showNotice("Could not find out which chapter comes " + (forward ? "next." : "before this one."));
            });
    }

    function chapterUrl(chapter, forward) {
        return data.chapterBase + chapter.id + "/view?page=" + (forward ? "1" : "last");
    }

    function showNotice(text) {
        if (!notice) { return; }
        notice.textContent = text;
        notice.hidden = false;
    }

    function hideNotice() {
        crossing = null;
        if (notice) { notice.hidden = true; }
    }

    var jumpPopup = document.getElementById("page-jump-popup");
    var jumpGo = document.getElementById("page-jump-go");
    if (counter && jumpPopup && pageInput && jumpGo && window.attachPopup) {
        var jumpPop = window.attachPopup(counter, jumpPopup, {
            onOpen: function () {
                pageInput.value = urls.length ? (index + 1) : "";
                pageInput.focus();
                pageInput.select();
            }
        });
        var jump = function () {
            var n = parseInt(pageInput.value, 10);
            if (isNaN(n) || urls.length === 0) { return; }
            index = Math.min(urls.length - 1, Math.max(0, n - 1));
            render();
            jumpPop.close();
        };
        jumpGo.addEventListener("click", jump);
        pageInput.addEventListener("keydown", function (e) {
            if (e.key === "Enter") { e.preventDefault(); jump(); }
        });
    }
    document.getElementById("hide-btn").addEventListener("click", function () { body.classList.toggle("ui-hidden"); });

    // --- View-settings popup (cogwheel) ---
    var settingsBtn = document.getElementById("settings-btn");
    var settingsPopup = document.getElementById("settings-popup");
    if (settingsBtn && settingsPopup && window.attachPopup) {
        window.attachPopup(settingsBtn, settingsPopup);
    }
    if (modeSelect) {
        modeSelect.addEventListener("change", function () {
            setMode(modeSelect.value.toLowerCase().replace(/_/g, "-"));
        });
    }
    if (workflowSelect) {
        workflowSelect.addEventListener("change", function () { selectWorkflow(workflowSelect.value); });
        loadWorkflows();
    }

    if (stage) {
        stage.addEventListener("click", function (e) {
            if (e.clientX > window.innerWidth / 2) { go(1); } else { go(-1); }
        });
    }

    document.addEventListener("keydown", function (e) {
        if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) { return; }
        // With a modifier the key is the browser's (Ctrl+S, Ctrl+D).
        if (e.ctrlKey || e.metaKey || e.altKey) { return; }
        var k = e.key.toLowerCase();
        if (e.key === "ArrowRight" || k === "d") { go(1, e.repeat); }
        else if (e.key === "ArrowLeft" || k === "a") { go(-1, e.repeat); }
        else if (k === "h") { body.classList.toggle("ui-hidden"); }
        else if (k === "b") { window.location.href = data.backHref || "/"; }
        else if (k === "s" && !e.repeat) { peek(!peeking); }
    });
})();
