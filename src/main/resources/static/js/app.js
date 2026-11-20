// Shared UI behaviour: nav toggle, delete confirmations, search type toggle, list filtering, review mode.
(function () {
    "use strict";

    // --- Reusable popup, on window so image-view.js shares the same open/close behaviour. ---
    function attachPopup(trigger, popup, opts) {
        opts = opts || {};
        function open() {
            popup.hidden = false;
            trigger.setAttribute("aria-expanded", "true");
            if (opts.onOpen) { opts.onOpen(); }
        }
        function close() {
            popup.hidden = true;
            trigger.setAttribute("aria-expanded", "false");
        }
        trigger.addEventListener("click", function (e) {
            e.stopPropagation();
            if (popup.hidden) { open(); } else { close(); }
        });
        document.addEventListener("click", function (e) {
            if (!popup.hidden && !trigger.contains(e.target) && !popup.contains(e.target)) {
                close();
            }
        });
        document.addEventListener("keydown", function (e) {
            if (e.key === "Escape" && !popup.hidden) { close(); }
        });
        return { open: open, close: close };
    }
    window.attachPopup = attachPopup;

    // --- A write refused because the library is busy ---
    //     A fetch that asks for JSON gets the reason with the 503. Nothing was changed, so the page stays as it
    //     is and the user can try again; reloading or posting again would only be refused the same way.
    var BUSY_FALLBACK = "The library is busy with a background task. Nothing was changed; try again in a moment.";
    var JSON_ANSWER = { Accept: "application/json" };

    function busyMessage(response) {
        return response.json()
            .then(function (answer) { return (answer && answer.message) || BUSY_FALLBACK; })
            .catch(function () { return BUSY_FALLBACK; });
    }

    document.querySelectorAll("[data-history-back]").forEach(function (button) {
        button.addEventListener("click", function () { window.history.back(); });
    });

    // --- Mobile nav toggle ---
    var toggle = document.querySelector(".nav-toggle");
    var links = document.querySelector(".nav-links");
    if (toggle && links) {
        toggle.addEventListener("click", function () {
            links.classList.toggle("open");
        });
    }

    // --- Confirm before any destructive action ---
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (form.classList && form.classList.contains("confirm-delete")) {
            var msg = form.getAttribute("data-confirm") || "Are you sure? This cannot be undone.";
            if (!window.confirm(msg)) {
                e.preventDefault();
            }
        }
    });

    // --- Search results: delete this page / everything matching ---
    //     The first submit is cancelled and only fetches counts, so the confirmation names real numbers.
    //     Registered before the long-running handler, so no spinner shows behind an unanswered question.
    function cardIds() {
        return Array.prototype.map.call(document.querySelectorAll(".card-grid .card[data-id]"), function (card) {
            return card.getAttribute("data-id");
        });
    }

    function bulkDeleteMessage(form, preview) {
        var page = form.getAttribute("data-scope") === "page";
        var lead = (page ? "Delete the " : "Delete all ") + preview.items.toLocaleString();
        var where = page ? " on this page" : " matching this search";
        if (form.getAttribute("data-kind") === "series") {
            return lead + " series" + where + " AND their " + preview.chapters.toLocaleString() +
                " chapter(s), including their page images on disk? This cannot be undone.";
        }
        return lead + " chapter(s)" + where + ", including their page images on disk? This cannot be undone.";
    }

    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (!form.classList || !form.classList.contains("bulk-delete")
            || form.getAttribute("data-confirmed") === "true") { return; }
        e.preventDefault();
        var ids = form.getAttribute("data-scope") === "page" ? cardIds() : null;
        if (ids && !ids.length) {
            window.alert("There is nothing on this page to delete.");
            return;
        }
        var url = form.getAttribute("data-count-url") + (ids ? ids.map(function (id) {
            return "&ids=" + encodeURIComponent(id);
        }).join("") : "");
        fetch(url, { headers: { Accept: "application/json" } })
            .then(function (r) {
                if (!r.ok) { throw new Error("HTTP " + r.status); }
                return r.json();
            })
            .then(function (preview) {
                if (!preview.items) {
                    window.alert("Nothing left to delete. Reload the page to see the current results.");
                    return;
                }
                if (!window.confirm(bulkDeleteMessage(form, preview))) { return; }
                if (ids) { ids.forEach(function (id) { form.appendChild(hidden("ids", id)); }); }
                form.setAttribute("data-confirmed", "true");
                if (form.requestSubmit) {
                    form.requestSubmit();
                } else {
                    markBusy(form);   // submit() fires no submit event, so nobody else would
                    form.submit();
                }
            })
            .catch(function (err) {
                window.alert("Could not count what would be deleted (" + err.message + "). Nothing was deleted.");
            });
    });

    // --- Long-running actions ---
    //     These POSTs redirect only when the whole library is done, so show a spinner and say the app stays
    //     usable in another tab: reading always, and writing too, because a change saved there waits for at most
    //     one slice. A "blocks-writes" form is one long write, so a change saved meanwhile waits for all of it.
    var BUSY_NOTE = "Working\u2026 this can take a while for a large library. " +
        "You can keep using the app in another tab meanwhile.";
    var BLOCKING_NOTE = "Working\u2026 this can take a while for a large library. " +
        "You can keep browsing in another tab meanwhile; a change saved there waits until this is done, " +
        "and may be refused as busy if that takes long.";

    //     The class may sit on one submit button instead, for a form whose other buttons are quick.
    //     "data-busy-note" on that button or the form replaces the note, for work that is long for another reason.
    function markBusy(form, submitter) {
        if (form.classList.contains("is-busy")) { return; }
        form.classList.add("is-busy");

        var btn = (submitter && submitter.tagName === "BUTTON" ? submitter : null)
            || form.querySelector('button[type="submit"]') || form.querySelector("button");
        if (btn) {
            var spinner = document.createElement("span");
            spinner.className = "spinner";
            spinner.setAttribute("aria-hidden", "true");
            btn.insertBefore(spinner, btn.firstChild);
            // Next tick: a disabled control is not submitted, and a button may carry a name/value.
            window.setTimeout(function () { btn.disabled = true; }, 0);
        }

        var note = document.createElement("p");
        note.className = "hint busy-note";
        note.setAttribute("role", "status");
        note.textContent = (submitter && submitter.getAttribute("data-busy-note"))
            || form.getAttribute("data-busy-note")
            || (form.classList.contains("blocks-writes") ? BLOCKING_NOTE : BUSY_NOTE);
        form.parentNode.insertBefore(note, form.nextSibling);
    }

    // Registered after the confirm handler, so a declined confirmation arrives defaultPrevented.
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (e.defaultPrevented || !form.classList) { return; }
        var submitter = e.submitter && e.submitter.classList ? e.submitter : null;
        if (submitter && submitter.classList.contains("long-running")) {
            markBusy(form, submitter);
        } else if (form.classList.contains("long-running")) {
            markBusy(form, null);
        }
    });

    // A page from the back-forward cache would come back still busy, or with a bulk delete already
    // confirmed; reloading renders it fresh and shows what the action changed.
    window.addEventListener("pageshow", function (e) {
        if (e.persisted && document.querySelector("form.is-busy, form[data-confirmed]")) {
            window.location.reload();
        }
    });

    // --- Date inputs: CSS hides the native picker indicator (Brave/Android cannot restyle it), so
    //     open the picker on click. ---
    document.addEventListener("click", function (e) {
        var el = e.target;
        if (el && el.matches && el.matches('input[type="date"]') && typeof el.showPicker === "function") {
            try {
                el.showPicker();
            } catch (err) {
                /* Unsupported or no user gesture: the field stays a plain date input. */
            }
        }
    });

    // --- Search Chapter/Series toggle: show/hide the gallery-id field ---
    var searchForm = document.getElementById("search-form");
    if (searchForm) {
        searchForm.querySelectorAll('input[name="type"]').forEach(function (radio) {
            radio.addEventListener("change", function () {
                var series = searchForm.querySelector('input[name="type"]:checked').value === "SERIES";
                searchForm.classList.toggle("is-series", series);
                searchForm.classList.toggle("is-chapter", !series);
            });
        });
    }

    // --- Manage: server-backed filter (a type can hold thousands of items). ---
    function debounce(fn, ms) {
        var t;
        return function () {
            var args = arguments, self = this;
            clearTimeout(t);
            t = setTimeout(function () { fn.apply(self, args); }, ms);
        };
    }

    function metaContent(name) {
        var el = document.querySelector('meta[name="' + name + '"]');
        return el ? el.getAttribute("content") : "";
    }
    window.metaContent = metaContent;

    // --- ComfyUI workflow dropdown, shared by the viewer and Settings so they cannot disagree. ---
    //     A choice the list lacks (ComfyUI down, file renamed) stays selected; becoming "None" would let a
    //     save store it.
    function fillWorkflowSelect(select, workflows, showInvalid) {
        var current = select.value;
        while (select.options.length > 1) { select.remove(1); }
        var listed = {};
        workflows.forEach(function (w) {
            if (!w.valid && !showInvalid) { return; }
            var option = new Option(w.valid ? w.name : w.name + " (cannot run)", w.name);
            option.disabled = !w.valid;
            if (!w.valid) { option.title = w.problem; }
            select.add(option);
            listed[w.name] = true;
        });
        if (current && !listed[current]) {
            select.add(new Option(current + " (not available right now)", current));
        }
        select.value = current;
    }
    window.fillWorkflowSelect = fillWorkflowSelect;

    function hidden(name, value) {
        var i = document.createElement("input");
        i.type = "hidden";
        i.name = name;
        i.value = value;
        return i;
    }

    var csrfParam = metaContent("csrf-param");
    var csrfToken = metaContent("csrf-token");

    function csrfInput() {
        return csrfParam ? hidden(csrfParam, csrfToken) : null;
    }

    // --- Manage: the per-section "Add rule" checkbox. A checkbox belongs to one form only, so it sits
    //     outside the section's forms and copies its state into their hidden createRule fields. ---
    function ruleToggleBox(type) {
        return document.querySelector('[data-rule-toggle="' + type + '"] input[type=checkbox]');
    }

    /** True when the section has no checkbox (the default). */
    function rulesWanted(type) {
        var box = ruleToggleBox(type);
        return !box || box.checked;
    }

    document.querySelectorAll("[data-rule-toggle]").forEach(function (label) {
        var box = label.querySelector('input[type=checkbox]');
        var section = label.closest(".manage-section");
        if (!box || !section) {
            return;
        }
        box.addEventListener("change", function () {
            section.querySelectorAll('input[name="createRule"]').forEach(function (field) {
                field.value = box.checked ? "true" : "false";
            });
        });
    });

    function buildItem(type, opt) {
        var li = document.createElement("li");
        li.className = "manage-item";

        var renameForm = document.createElement("form");
        renameForm.method = "post";
        renameForm.action = "/manage/rename";
        renameForm.className = "inline-form rename-form";
        var nameInput = document.createElement("input");
        nameInput.type = "text";
        nameInput.name = "name";
        nameInput.value = opt.label;
        nameInput.className = "rename-input";
        var renameBtn = document.createElement("button");
        renameBtn.type = "submit";
        renameBtn.className = "btn";
        renameBtn.textContent = "Rename";
        // Must match the template's fields, or filtering the list would silently reset the rule choice.
        var rules = rulesWanted(type) ? "true" : "false";
        [csrfInput(), hidden("type", type), hidden("id", opt.id), hidden("createRule", rules),
            nameInput, renameBtn]
            .forEach(function (el) { if (el) { renameForm.appendChild(el); } });

        var deleteForm = document.createElement("form");
        deleteForm.method = "post";
        deleteForm.action = "/manage/remove";
        deleteForm.className = "inline-form confirm-delete long-running blocks-writes";
        var deleteBtn = document.createElement("button");
        deleteBtn.type = "submit";
        deleteBtn.className = "btn btn-danger";
        deleteBtn.textContent = "Delete";
        [csrfInput(), hidden("type", type), hidden("id", opt.id), hidden("createRule", rules), deleteBtn]
            .forEach(function (el) { if (el) { deleteForm.appendChild(el); } });

        li.appendChild(renameForm);
        li.appendChild(deleteForm);
        return li;
    }

    document.querySelectorAll(".list-filter").forEach(function (input) {
        var list = document.getElementById(input.getAttribute("data-target"));
        var type = input.getAttribute("data-type");
        var hint = input.nextElementSibling;
        var limit = input.getAttribute("data-limit") || "3";
        if (!list || !type) {
            return;
        }
        var fetchItems = debounce(function () {
            var q = input.value.trim();
            fetch("/manage/items?type=" + encodeURIComponent(type) + "&q=" + encodeURIComponent(q))
                .then(function (r) { return r.ok ? r.json() : []; })
                .then(function (items) {
                    list.innerHTML = "";
                    if (hint) {
                        hint.textContent = q ? "Sorted by name." : "Showing the " + limit + " most recent.";
                    }
                    if (!items.length) {
                        var empty = document.createElement("li");
                        empty.className = "empty";
                        empty.textContent = q ? "No matches." : "None yet.";
                        list.appendChild(empty);
                        return;
                    }
                    items.forEach(function (opt) { list.appendChild(buildItem(type, opt)); });
                })
                .catch(function () { /* leave the current list in place on a transient error */ });
        }, 200);
        input.addEventListener("input", fetchItems);
    });

    // --- Language autocomplete ---
    document.querySelectorAll('input[data-language-autocomplete]').forEach(function (input) {
        var wrapper = input.parentElement;
        var dropdown = document.createElement("div");
        dropdown.className = "lang-dropdown";
        dropdown.hidden = true;
        wrapper.style.position = "relative";
        wrapper.appendChild(dropdown);

        var activeIndex = -1;

        function items() { return dropdown.querySelectorAll(".lang-option"); }

        function highlight(idx) {
            var opts = items();
            opts.forEach(function (o, i) { o.classList.toggle("active", i === idx); });
            activeIndex = idx;
        }

        function accept(value) {
            input.value = value;
            dropdown.hidden = true;
            activeIndex = -1;
        }

        function populate(list) {
            dropdown.innerHTML = "";
            if (!list.length) { dropdown.hidden = true; return; }
            list.forEach(function (lang) {
                var opt = document.createElement("div");
                opt.className = "lang-option";
                opt.textContent = lang;
                opt.addEventListener("mousedown", function (e) { e.preventDefault(); accept(lang); });
                dropdown.appendChild(opt);
            });
            dropdown.hidden = false;
            activeIndex = -1;
        }

        var fetchLangs = debounce(function () {
            fetch("/api/languages?q=" + encodeURIComponent(input.value.trim()))
                .then(function (r) { return r.ok ? r.json() : []; })
                .then(populate)
                .catch(function () { dropdown.hidden = true; });
        }, 150);

        input.addEventListener("input", fetchLangs);
        input.addEventListener("focus", fetchLangs);

        input.addEventListener("keydown", function (e) {
            var opts = items();
            if (e.key === "ArrowDown") {
                e.preventDefault();
                highlight(Math.min(activeIndex + 1, opts.length - 1));
            } else if (e.key === "ArrowUp") {
                e.preventDefault();
                highlight(Math.max(activeIndex - 1, 0));
            } else if ((e.key === "Enter" || e.key === "Tab") && activeIndex >= 0 && !dropdown.hidden) {
                e.preventDefault();
                accept(opts[activeIndex].textContent);
            } else if (e.key === "Escape") {
                dropdown.hidden = true;
                activeIndex = -1;
            }
        });

        document.addEventListener("click", function (e) {
            if (!wrapper.contains(e.target)) { dropdown.hidden = true; activeIndex = -1; }
        });
    });

    // --- Chapter edit: delete a page image in place, so a long chapter does not scroll back each time ---
    //     Only a 204 counts as done: an expired login also answers with a redirect. A busy library keeps the page;
    //     anything else falls back to a plain post. Registered after confirm-delete, so a declined confirmation
    //     arrives defaultPrevented.
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (e.defaultPrevented || !form.classList || !form.classList.contains("page-delete")) { return; }
        e.preventDefault();
        var thumb = form.closest(".page-thumb");
        var body = new URLSearchParams(new FormData(form));
        body.append("inPlace", "true");
        form.querySelector("button").disabled = true;
        thumb.classList.add("is-deleting");
        fetch(form.action, { method: "POST", body: body, redirect: "manual", headers: JSON_ANSWER })
            .then(function (r) {
                if (r.status === 503) {
                    return busyMessage(r).then(function (message) {
                        thumb.classList.remove("is-deleting");
                        form.querySelector("button").disabled = false;
                        window.alert(message);
                    });
                }
                if (r.status !== 204) { throw new Error("HTTP " + r.status); }
                var grid = thumb.parentNode;
                var thumbs = Array.prototype.slice.call(grid.querySelectorAll(".page-thumb"));
                var at = thumbs.indexOf(thumb);
                var neighbour = thumbs[at + 1] || thumbs[at - 1];
                thumb.remove();
                if (neighbour) {
                    // The focused button is gone; keep a keyboard user in place.
                    neighbour.querySelector(".page-delete button").focus({ preventScroll: true });
                } else {
                    grid.querySelector(".empty").hidden = false;
                }
            })
            .catch(function () {
                form.submit();   // fires no submit event, so no confirmation or handler runs again
            });
    });

    // --- Favourite button on the chapter and series pages, and the heart in an artist's chip: flipped in place, ---
    //     so the page keeps its place. Only a 204 counts as done: an expired login also answers with a redirect.
    //     A busy library keeps the button as it was; anything else falls back to a plain post. The form always
    //     sends the new value; the button's data-on-*/data-off-* attributes hold its text and title per state.
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (e.defaultPrevented || !form.classList || !form.classList.contains("favourite-toggle")) { return; }
        e.preventDefault();
        var button = form.querySelector("button");
        var value = form.querySelector('input[name="favourite"]');
        var body = new URLSearchParams(new FormData(form));
        body.append("inPlace", "true");
        button.disabled = true;
        fetch(form.action, { method: "POST", body: body, redirect: "manual", headers: JSON_ANSWER })
            .then(function (r) {
                if (r.status === 503) {
                    return busyMessage(r).then(function (message) { window.alert(message); });
                }
                if (r.status !== 204) { throw new Error("HTTP " + r.status); }
                var now = value.value === "true";
                value.value = String(!now);
                button.setAttribute("aria-pressed", String(now));
                var state = now ? "on" : "off";
                button.title = button.getAttribute("data-" + state + "-title");
                button.textContent = button.getAttribute("data-" + state + "-text");
            })
            .catch(function () {
                form.submit();   // fires no submit event, so no handler runs again
            })
            .finally(function () { button.disabled = false; });
    });

    // --- Divide a chapter ---
    //     Unmarking hides and disables a divider instead of removing it: re-marking brings back what was typed,
    //     and a disabled input is neither sent nor validated.
    (function () {
        var form = document.querySelector("form[data-divide]");
        if (!form) { return; }
        var grid = form.querySelector(".divide-grid");
        var template = document.getElementById("divide-part-template");
        var pages = Array.prototype.slice.call(grid.querySelectorAll(".divide-page"));
        var keptTitle = form.querySelector('input[name="keptTitle"]');
        var partStatus = form.querySelector('select[name="partStatus"]');
        var keptLabel = form.querySelector(".divide-part-kept [data-divide-label]");
        var summary = form.querySelector("[data-divide-summary]");
        var submit = form.querySelector("[data-divide-submit]");
        var defaultTitle = form.getAttribute("data-default-title") || "";

        function dividerOf(page) {
            var before = page.previousElementSibling;
            return before && before.classList.contains("divide-part") ? before : null;
        }

        function addDivider(page) {
            var divider = template.content.firstElementChild.cloneNode(true);
            var input = divider.querySelector("input");
            input.name = "titles[" + page.getAttribute("data-page") + "]";
            input.value = defaultTitle;
            grid.insertBefore(divider, page);
            return divider;
        }

        function pagesText(from, to) {
            return from === to ? "page " + from : "pages " + from + "–" + to;
        }

        /** Also brings the dividers in step with the marks. */
        function marked() {
            var starts = [];
            pages.forEach(function (page, i) {
                var box = page.querySelector('input[name="starts"]');
                var on = !!(box && box.checked);
                var divider = dividerOf(page) || (on ? addDivider(page) : null);
                if (divider) {
                    divider.hidden = !on;
                    divider.querySelector("input").disabled = !on;
                }
                if (on) { starts.push({ at: i, divider: divider }); }
            });
            return starts;
        }

        function ranges(starts) {
            var bounds = [0].concat(starts.map(function (start) { return start.at; }), [pages.length]);
            return bounds.slice(0, -1).map(function (from, n) { return pagesText(from + 1, bounds[n + 1]); });
        }

        function refresh() {
            var starts = marked();
            var texts = ranges(starts);
            keptLabel.textContent = "Part 1 · " + texts[0] + " · stays as this chapter";
            starts.forEach(function (start, n) {
                start.divider.querySelector("[data-divide-label]").textContent =
                    "Part " + (n + 2) + " · " + texts[n + 1] + " · new chapter";
            });
            submit.disabled = !starts.length;
            submit.textContent = starts.length ? "Divide into " + (starts.length + 1) + " chapters" : "Divide";
            summary.textContent = starts.length
                ? starts.length + " new chapter(s), and this one keeps part 1."
                : "Mark the first page of each chapter inside this one.";
            return starts;
        }

        grid.addEventListener("change", function (e) {
            if (e.target.matches('input[name="starts"]')) { refresh(); }
        });
        grid.addEventListener("click", function (e) {
            var unmark = e.target.closest("[data-divide-unmark]");
            if (!unmark) { return; }
            var page = unmark.closest(".divide-part").nextElementSibling;
            var box = page && page.querySelector('input[name="starts"]');
            if (box) {
                box.checked = false;
                refresh();
                box.focus({ preventScroll: true });
            }
        });

        // Names every part and the status (picked far up the page), because chapters cannot be joined back.
        form.addEventListener("submit", function (e) {
            var starts = refresh();
            if (!starts.length) {
                e.preventDefault();
                return;
            }
            var texts = ranges(starts);
            var lines = ["Part 1 (" + texts[0] + "): " + keptTitle.value];
            starts.forEach(function (start, n) {
                lines.push("Part " + (n + 2) + " (" + texts[n + 1] + "): " + start.divider.querySelector("input").value);
            });
            var status = partStatus.options[partStatus.selectedIndex].text;
            if (!window.confirm("Divide this chapter into " + (starts.length + 1) + " chapters?\n\n" + lines.join("\n") +
                "\n\nStatus of the new chapters: " + status +
                "\n\nChapters cannot be joined back together in the app, so this cannot be undone here.")) {
                e.preventDefault();
                return;
            }
            // So the click does not look ignored; is-busy also makes pageshow reload a cached copy.
            form.classList.add("is-busy");
            window.setTimeout(function () { submit.disabled = true; }, 0);
        });

        refresh();
    })();

    // --- Link chapters: "Select all", and a question before chapters leave other series ---
    //     Moving chapters out of a series can empty it, and an empty series is deleted, so ask first.
    document.querySelectorAll("[data-select-all]").forEach(function (all) {
        var form = all.closest("form");
        all.addEventListener("change", function () {
            form.querySelectorAll('input[name="chapterIds"]').forEach(function (box) { box.checked = all.checked; });
        });
    });

    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (e.defaultPrevented || !form.classList || !form.classList.contains("link-chapters")) { return; }
        var picked = form.querySelectorAll('input[name="chapterIds"]:checked');
        if (!picked.length) {
            e.preventDefault();
            window.alert("Tick the chapters to link first.");
            return;
        }
        var moved = 0;
        var perSeries = {};
        picked.forEach(function (box) {
            var series = box.getAttribute("data-series-id");
            if (!series) { return; }
            moved++;
            perSeries[series] = (perSeries[series] || 0) + 1;
        });
        if (!moved) { return; }
        var emptied = Object.keys(perSeries).filter(function (series) {
            var box = form.querySelector('input[data-series-id="' + series + '"]');
            return perSeries[series] >= parseInt(box.getAttribute("data-series-chapters"), 10);
        }).length;
        var question = moved + " of the chapters picked will be moved here out of the series they are in now." +
            (emptied ? " " + emptied + " series will be left with no chapters, and deleted." : "") + " Link them?";
        if (!window.confirm(question)) { e.preventDefault(); }
    });

    // --- Series edit: inline chapter-number Update button ---
    var chapterGrid = document.querySelector('.card-grid[data-series-id]');
    if (chapterGrid) {
        var gridSeriesId = chapterGrid.getAttribute('data-series-id');
        chapterGrid.addEventListener('click', function (e) {
            var btn = e.target.closest('.chapter-num-update');
            if (!btn) { return; }
            var row = btn.closest('.chapter-num-row');
            var chapterId = row && row.getAttribute('data-chapter-id');
            var input = row && row.querySelector('.chapter-num-input');
            var val = input && input.value.trim();
            if (!chapterId || !val) { return; }
            var body = (csrfParam ? csrfParam + '=' + encodeURIComponent(csrfToken) + '&' : '')
                     + 'chapterNum=' + encodeURIComponent(val);
            btn.disabled = true;
            fetch('/series/' + gridSeriesId + '/chapters/' + chapterId + '/num', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
                body: body
            }).then(function (r) {
                if (r.status === 503) {
                    // Said, not only marked: a bare ✗ would not tell a busy library from a wrong number.
                    return busyMessage(r).then(function (message) {
                        window.alert(message);
                        throw new Error(message);
                    });
                }
                if (r.status === 400) {
                    return r.json().catch(function () { return {}; }).then(function (answer) {
                        var message = answer.message || 'Not a chapter number.';
                        window.alert(message);
                        throw new Error(message);
                    });
                }
                if (!r.ok) { throw new Error(); }
                return r.json().then(function (answer) { input.value = answer.chapterNum; });
            }).then(function () {
                btn.textContent = '✓';
                // Empty numbers sort last.
                var cards = Array.prototype.slice.call(chapterGrid.querySelectorAll('.edit-card'));
                cards.sort(function (a, b) {
                    var na = parseFloat(a.querySelector('.chapter-num-input').value);
                    var nb = parseFloat(b.querySelector('.chapter-num-input').value);
                    if (isNaN(na)) { na = Infinity; }
                    if (isNaN(nb)) { nb = Infinity; }
                    return na - nb;
                });
                cards.forEach(function (c) { chapterGrid.appendChild(c); });
            }).catch(function () {
                btn.textContent = '✗';
            }).finally(function () {
                btn.disabled = false;
                setTimeout(function () { btn.textContent = 'Update'; }, 1500);
            });
        });
    }

    // --- Search results: jump to a page via popup ---
    document.querySelectorAll(".pagination").forEach(function (nav) {
        var btn = nav.querySelector(".page-jump-btn");
        var popup = nav.querySelector(".page-jump-popup");
        var input = nav.querySelector(".page-jump-input");
        var go = nav.querySelector(".page-jump-go");
        if (!btn || !popup || !input || !go) { return; }
        var baseQuery = nav.getAttribute("data-base-query") || "";
        var total = parseInt(nav.getAttribute("data-total-pages"), 10) || 1;

        attachPopup(btn, popup, { onOpen: function () { input.value = ""; input.focus(); } });

        function jump() {
            var n = parseInt(input.value, 10);
            if (isNaN(n)) { return; }
            n = Math.min(total, Math.max(1, n));
            window.location.href = "/search/results?" + baseQuery + "&page=" + (n - 1);
        }
        go.addEventListener("click", jump);
        input.addEventListener("keydown", function (e) {
            if (e.key === "Enter") { e.preventDefault(); jump(); }
        });
    });
    // --- Download queue: reload while there is something to watch ---
    //     A reload, not a polling endpoint, can never disagree with what the server renders. It stops once
    //     the queue drains or is paused, so an idle tab costs nothing. A reload keeps the scroll position.
    (function () {
        var panel = document.getElementById("download-queue");
        if (!panel) { return; }
        var timer = null;
        var inFlight = 0;
        function reloadSoon() {
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                // A reload would cut off a row's answer, and the row would be left half-done on screen.
                if (inFlight > 0) { reloadSoon(); } else { window.location.reload(); }
            }, 3000);
        }
        if (panel.getAttribute("data-active") === "true") { reloadSoon(); }

        // --- Failed, rejected and merged rows: retry or remove in place, so a long list does not jump back to the top ---
        //     Only a 204 counts as done: an expired login also answers with a redirect. A busy library keeps the
        //     row; anything else falls back to a plain post. A retried row is waiting now, so the page reloads
        //     shortly to show it there. Registered after confirm-delete, so a declined confirmation arrives
        //     defaultPrevented.
        document.addEventListener("submit", function (e) {
            var form = e.target;
            if (e.defaultPrevented || !form.classList || !form.classList.contains("queue-in-place")) { return; }
            e.preventDefault();
            var row = form.closest(".queue-item");
            var buttons = row.querySelectorAll("button");
            var body = new URLSearchParams(new FormData(form));
            body.append("inPlace", "true");
            buttons.forEach(function (b) { b.disabled = true; });
            row.classList.add("is-busy");
            inFlight++;
            fetch(form.action, { method: "POST", body: body, redirect: "manual", headers: JSON_ANSWER })
                .then(function (r) {
                    if (r.status === 503) {
                        return busyMessage(r).then(function (message) {
                            row.classList.remove("is-busy");
                            buttons.forEach(function (b) { b.disabled = false; });
                            window.alert(message);
                        });
                    }
                    if (r.status !== 204) { throw new Error("HTTP " + r.status); }
                    var list = row.parentNode;
                    var section = row.closest("section");
                    row.remove();
                    // Each list has its own count, so only the one this row left loses a row.
                    if (section) {
                        section.querySelectorAll("[data-queue-count]").forEach(function (count) {
                            count.textContent = Math.max(0, (parseInt(count.textContent, 10) || 0) - 1);
                        });
                    }
                    // The list is capped: an empty one may have more rows behind it, or none left at all.
                    if (!list.querySelector(".queue-item")) { window.clearTimeout(timer); window.location.reload(); }
                    else if (/\/retry$/.test(form.action)) { reloadSoon(); }
                })
                .catch(function () {
                    form.submit();   // fires no submit event, so no handler runs again
                })
                .finally(function () { inFlight--; });
        });
    })();
    // --- Image Compression dropdowns: "Custom" navigates, it does not select ---
    //     "Custom" is not a mode and must never be stored, so picking it restores the previous choice.
    document.querySelectorAll(".compression-mode-select").forEach(function (select) {
        var previous = select.value;
        select.addEventListener("change", function () {
            var option = select.options[select.selectedIndex];
            if (option && option.getAttribute("data-custom") === "true") {
                select.value = previous;
                window.location.href = select.getAttribute("data-custom-href") || "/compression-modes/new";
                return;
            }
            previous = select.value;
        });
    });

    // --- Fields that belong to some values of a select only (a subscription's site) ---
    //     Each [data-shown-for] lists the values it belongs to, space-separated. Hidden, never disabled: hidden
    //     fields are still sent, so switching back finds them as they were. Without JavaScript all of them show.
    document.querySelectorAll("select[data-shows]").forEach(function (select) {
        var form = select.closest("form");
        if (!form) { return; }
        var sync = function () {
            form.querySelectorAll("[data-shown-for]").forEach(function (element) {
                var values = (element.getAttribute("data-shown-for") || "").split(/\s+/);
                element.hidden = values.indexOf(select.value) < 0;
            });
        };
        select.addEventListener("change", sync);
        sync();
    });

    // --- Fields that belong to a ticked checkbox only (merging duplicates beside avoiding them) ---
    //     [data-shown-with] names the checkbox's id. Hidden, never disabled, as above; the server ignores the field
    //     while the box is not ticked.
    document.querySelectorAll("[data-shown-with]").forEach(function (element) {
        var box = document.getElementById(element.getAttribute("data-shown-with"));
        if (!box) { return; }
        var sync = function () { element.hidden = !box.checked; };
        box.addEventListener("change", sync);
        sync();
    });

    // --- Download page: gallery-dl's choices only for pasted links that read them ---
    //     Each [data-shown-for-links] lists text that every link of a source reading it contains, so such a link is
    //     never missed; text that merely mentions a site shows it for nothing. Hidden, never disabled, like the
    //     fields above. Whatever holds a field error always shows.
    document.querySelectorAll("textarea[data-shows-for-links]").forEach(function (links) {
        var form = links.closest("form");
        if (!form) { return; }
        var shown = form.querySelectorAll("[data-shown-for-links]");
        var sync = function () {
            var text = links.value.toLowerCase();
            shown.forEach(function (element) {
                var markers = element.getAttribute("data-shown-for-links").split(/\s+/).filter(Boolean);
                element.hidden = !element.querySelector(".field-error")
                    && !markers.some(function (marker) { return text.indexOf(marker) >= 0; });
            });
        };
        links.addEventListener("input", sync);
        sync();
    });

    // --- Download page: the delay starts lower when no pasted link needs the Settings one ---
    //     Only while the field is untouched: once the user types a delay, pasting another link never replaces it.
    //     A paste without a gallery-dl link keeps the Settings default, since nothing would read the faster one.
    document.querySelectorAll("[data-delay-fast]").forEach(function (options) {
        var form = options.closest("form");
        var links = form && form.querySelector("textarea[name='links']");
        var field = options.querySelector("input[name='requestDelay']");
        if (!links || !field || field.getAttribute("data-delay-edited") === "true") { return; }
        var galleryDlMarkers = (options.getAttribute("data-shown-for-links") || "").split(/\s+/).filter(Boolean);
        var slowMarkers = (options.getAttribute("data-delay-slow-markers") || "").split(/\s+/).filter(Boolean);
        var slowDelay = options.getAttribute("data-delay-default");
        var fastDelay = options.getAttribute("data-delay-fast");
        var edited = false;
        var sync = function () {
            if (edited) { return; }
            var text = links.value.toLowerCase();
            var hasGalleryDl = galleryDlMarkers.some(function (marker) { return text.indexOf(marker) >= 0; });
            var needsSlow = slowMarkers.some(function (marker) { return text.indexOf(marker) >= 0; });
            field.value = hasGalleryDl && !needsSlow ? fastDelay : slowDelay;
        };
        field.addEventListener("input", function () { edited = true; });
        links.addEventListener("input", sync);
        sync();
    });

    // --- Settings: the password asked for when login is turned on while none exists ---
    //     Required only while "Require login" is ticked, so saving another setting never asks for one. The
    //     server enforces it anyway; this only saves a round trip.
    document.querySelectorAll("[data-login-password-setup]").forEach(function (setup) {
        var form = setup.closest("form");
        var box = form && form.querySelector("input[name='loginRequired']");
        if (!box) { return; }
        var fields = setup.querySelectorAll("input[type='password']");
        var sync = function () {
            setup.hidden = !box.checked;
            fields.forEach(function (field) { field.required = box.checked; });
        };
        box.addEventListener("change", sync);
        sync();
    });

    // A mistyped password with login required locks the user out until the password file is deleted.
    document.querySelectorAll("input[data-repeat-of]").forEach(function (repeat) {
        var first = document.getElementById(repeat.getAttribute("data-repeat-of"));
        if (!first) { return; }
        var check = function () {
            repeat.setCustomValidity(repeat.value && repeat.value !== first.value ? "The two passwords do not match." : "");
        };
        repeat.addEventListener("input", check);
        first.addEventListener("input", check);
    });

    // --- Settings: follow a ComfyUI the app is starting ---
    //     Fetched after load so slow ComfyUI and cache calls never hold up the page; polled only while
    //     starting. The workflow lists are filled in place, because a reload would lose unsaved form edits.
    (function () {
        var section = document.getElementById("comfyui-status");
        if (!section) { return; }
        var statusUrl = section.getAttribute("data-status-url");
        var workflowsUrl = section.getAttribute("data-workflows-url");
        var cacheSizeUrl = section.getAttribute("data-cache-size-url");
        var summary = section.querySelector("[data-comfyui-summary]");
        var message = section.querySelector("[data-comfyui-message]");
        var consoleBox = section.querySelector("[data-comfyui-console-box]");
        var consoleText = section.querySelector("[data-comfyui-console]");
        var stopBtn = section.querySelector("[data-comfyui-stop]");
        var state = section.getAttribute("data-state");

        function scrollConsole() {
            if (consoleText) { consoleText.scrollTop = consoleText.scrollHeight; }
        }

        function span(className, text) {
            var el = document.createElement("span");
            el.className = className;
            el.textContent = text;
            return el;
        }

        function refreshWorkflows() {
            fetch(workflowsUrl, { headers: { Accept: "application/json" } })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (list) {
                    if (!list || !list.reachable) { return; }
                    var select = document.querySelector("[data-comfyui-workflow-select]");
                    // Only workflows that can run: the check list below says what is wrong with the others.
                    if (select) { fillWorkflowSelect(select, list.workflows, false); }
                    // Must match settings.html; a list the user opened stays open.
                    var check = document.querySelector("[data-comfyui-workflow-check]");
                    if (check) {
                        var wasOpen = !!check.querySelector("details[open]");
                        var parts = [];
                        if (list.problem) {
                            var p = document.createElement("p");
                            p.className = "hint";
                            p.textContent = list.problem;
                            parts.push(p);
                        }
                        if (list.workflows.length) {
                            var ready = list.workflows.filter(function (w) { return w.valid; }).length;
                            var broken = list.workflows.length - ready;
                            var details = document.createElement("details");
                            details.className = "workflow-details" + (broken > 0 ? " has-problems" : "");
                            details.open = wasOpen;
                            var summary = document.createElement("summary");
                            summary.textContent = "Workflows: " + ready + " ready"
                                + (broken > 0 ? ", " + broken + " cannot run" : "");
                            details.appendChild(summary);
                            var ul = document.createElement("ul");
                            ul.className = "workflow-list";
                            list.workflows.forEach(function (w) {
                                var li = document.createElement("li");
                                li.className = w.valid ? "ok" : "bad";
                                li.appendChild(span("workflow-name", w.name));
                                li.appendChild(span("workflow-state", w.valid ? "Ready" : w.problem));
                                ul.appendChild(li);
                            });
                            details.appendChild(ul);
                            parts.push(details);
                        }
                        check.replaceChildren.apply(check, parts);
                    }
                })
                .catch(function () { /* the list stays as rendered */ });
        }

        function poll() {
            fetch(statusUrl, { headers: { Accept: "application/json" } })
                .then(function (r) { if (!r.ok) { throw new Error("HTTP " + r.status); } return r.json(); })
                .then(function (status) {
                    summary.textContent = status.summary;
                    message.textContent = status.message || "";
                    message.hidden = !status.message;
                    consoleText.textContent = status.console.join("\n");
                    consoleBox.hidden = status.console.length === 0;
                    scrollConsole();
                    if (stopBtn) { stopBtn.disabled = !status.launched; }
                    if (status.state === "STARTING") {
                        window.setTimeout(poll, 2000);
                    } else if (state === "STARTING") {
                        refreshWorkflows();
                    }
                    state = status.state;
                })
                .catch(function () { window.setTimeout(poll, 5000); });
        }

        // A collapsed <pre> has no height to scroll, so scroll to the latest line when opened.
        if (consoleBox) { consoleBox.addEventListener("toggle", scrollConsole); }
        poll();
        var cacheSize = section.querySelector("[data-comfyui-cache-size]");
        if (cacheSize) {
            fetch(cacheSizeUrl, { headers: { Accept: "application/json" } })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (answer) { if (answer) { cacheSize.textContent = answer.size; } })
                .catch(function () { /* the placeholder stays */ });
        }
    })();

    // --- Review mode: walk one page of results item by item ---
    //     The list lives in sessionStorage, so the server keeps no review state. The position is looked up
    //     from the URL, never stored, so it cannot disagree with the page on screen.
    var REVIEW_KEY = "hentie.review";

    function loadReview() {
        try {
            var state = JSON.parse(window.sessionStorage.getItem(REVIEW_KEY));
            return state && Array.isArray(state.items) && typeof state.returnUrl === "string" ? state : null;
        } catch (err) {
            return null;
        }
    }

    function reviewUrl(href) {
        return href + (href.indexOf("?") < 0 ? "?" : "&") + "review=true";
    }

    var reviewStart = document.querySelector("[data-review-start]");
    if (reviewStart) {
        reviewStart.addEventListener("click", function () {
            var items = Array.prototype.map.call(document.querySelectorAll(".card-grid .card"), function (card) {
                return card.getAttribute("href");
            });
            if (!items.length) { return; }
            try {
                window.sessionStorage.setItem(REVIEW_KEY, JSON.stringify({
                    items: items,
                    returnUrl: window.location.pathname + window.location.search
                }));
            } catch (err) {
                window.alert("Review mode needs this browser's session storage, which it refuses here.");
                return;
            }
            window.location.assign(reviewUrl(items[0]));
        });
    }

    var reviewBar = document.querySelector("[data-review-bar]");
    var review = reviewBar ? loadReview() : null;
    var reviewPos = review ? review.items.indexOf(window.location.pathname) : -1;
    if (reviewPos >= 0) {
        reviewBar.hidden = false;
        reviewBar.querySelector("[data-review-progress]").textContent =
            "Review " + (reviewPos + 1) + " / " + review.items.length;

        // replace(), not assign(): Back returns to the results, not through items the review deleted.
        var finishReview = function () {
            try {
                window.sessionStorage.removeItem(REVIEW_KEY);
            } catch (err) {
                // Storage refused: the stale list is harmless, nothing shows it without ?review=true.
            }
            window.location.replace(review.returnUrl);
        };
        var nextItem = function () {
            if (reviewPos + 1 < review.items.length) {
                window.location.replace(reviewUrl(review.items[reviewPos + 1]));
            } else {
                finishReview();
            }
        };
        reviewBar.querySelector("[data-review-skip]").addEventListener("click", nextItem);
        reviewBar.querySelector("[data-review-exit]").addEventListener("click", finishReview);

        // One action at a time: the controls are disabled while one runs, and the keys wait the same way.
        var reviewBusy = false;
        var reviewControls = function (disabled) {
            reviewBusy = disabled;
            reviewBar.querySelectorAll("button, select").forEach(function (c) { c.disabled = disabled; });
        };

        // Posted by fetch because only this page knows where to go next; following the server's redirect would only
        // render a page nobody sees. done() runs once the server took it.
        var postReview = function (form, body, done, onBusy) {
            reviewControls(true);
            fetch(form.action, { method: "POST", body: body, redirect: "manual", headers: JSON_ANSWER })
                .then(function (r) {
                    // Busy: nothing was done, so the same item stays, ready for another press.
                    if (r.status === 503) {
                        return busyMessage(r).then(function (message) {
                            reviewControls(false);
                            onBusy();
                            window.alert(message);
                        });
                    }
                    // 404: deleted meanwhile. Reloading would show an error page with no review bar, so move on.
                    if (r.status === 404) {
                        nextItem();
                        return;
                    }
                    if (!r.ok && r.type !== "opaqueredirect") {
                        throw new Error("HTTP " + r.status);
                    }
                    done();
                })
                .catch(function (err) {
                    window.alert("That did not work (" + err.message + "). This item is reloaded as it is now.");
                    window.location.reload();
                });
        };

        // Registered after confirm-delete.
        document.addEventListener("submit", function (e) {
            var form = e.target;
            if (e.defaultPrevented || !form.classList || !form.classList.contains("review-action")) { return; }
            e.preventDefault();
            var clicked = form.querySelector("button");
            var spinner = document.createElement("span");
            spinner.className = "spinner";
            spinner.setAttribute("aria-hidden", "true");
            clicked.insertBefore(spinner, clicked.firstChild);
            postReview(form, new URLSearchParams(new FormData(form)), nextItem, function () { spinner.remove(); });
        });

        // A score stays on the item, so the page is reloaded to show it (and the status, when a key marked it).
        var scoreSelect = reviewBar.querySelector("[data-review-score]");
        var savedScore = scoreSelect.value;
        var postScore = function (markReviewed) {
            var body = new URLSearchParams(new FormData(scoreSelect.form));
            if (markReviewed) { body.append("markReviewed", "true"); }
            postReview(scoreSelect.form, body, function () { window.location.reload(); },
                function () { scoreSelect.value = savedScore; });
        };
        scoreSelect.addEventListener("change", function () { postScore(false); });

        // Keys do what their button does, so "e" still asks before deleting. "1"-"9" are scores 1-9, "0" is 10.
        document.addEventListener("keydown", function (e) {
            if (e.defaultPrevented || e.repeat || e.ctrlKey || e.altKey || e.metaKey || reviewBusy) { return; }
            var target = e.target;
            if (target && (target.isContentEditable || /^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName))) { return; }
            var key = e.key.toLowerCase();
            if (/^[0-9]$/.test(key)) {
                e.preventDefault();
                scoreSelect.value = key === "0" ? "10" : key;
                postScore(true);
                return;
            }
            var button = reviewBar.querySelector('[data-review-key="' + key + '"]');
            if (button) {
                e.preventDefault();
                button.click();
            }
        });
    }

    // --- Image compression rules: a name box suggests names of the kind chosen beside it ---
    //     A datalist, not a token field: a rule may name a value the library does not have yet, so typed text
    //     counts as it is, and suggestions only spare the typing.
    function wireMetadataValue(row) {
        var select = row.querySelector(".metadata-type-select");
        var input = row.querySelector(".metadata-value-input");
        var list = row.querySelector("datalist");
        if (!select || !input || !list) { return; }
        var timer;
        var suggest = function () {
            clearTimeout(timer);
            timer = setTimeout(function () {
                fetch("/api/autocomplete/" + encodeURIComponent(select.value) + "?q="
                        + encodeURIComponent(input.value.trim()))
                    .then(function (r) { return r.ok ? r.json() : []; })
                    .then(function (options) {
                        list.innerHTML = "";
                        options.forEach(function (o) {
                            var option = document.createElement("option");
                            option.value = o.label;
                            list.appendChild(option);
                        });
                    })
                    .catch(function () { list.innerHTML = ""; });
            }, 180);
        };
        input.addEventListener("input", suggest);
        input.addEventListener("focus", suggest);
        select.addEventListener("change", function () { list.innerHTML = ""; });
    }

    document.querySelectorAll(".metadata-value-row").forEach(wireMetadataValue);

    // --- Image compression rule form: condition rows ---
    //     Rows post as two lists in step (conditionTypes, conditionValues), so adding or removing one renumbers
    //     nothing. A copy of the empty last row is the template, taken before anyone types into it.
    document.querySelectorAll("[data-rule-conditions]").forEach(function (container) {
        var form = container.closest("form");
        var template = container.querySelector("[data-condition-template]");
        var addButton = form && form.querySelector("[data-add-condition]");
        var blank = template ? template.cloneNode(true) : null;
        var added = 0;
        var wireRemove = function (row) {
            var remove = row.querySelector("[data-remove-condition]");
            if (remove) {
                remove.addEventListener("click", function () { row.parentNode.removeChild(row); });
            }
        };
        container.querySelectorAll(".rule-condition").forEach(wireRemove);
        if (blank && addButton) {
            blank.removeAttribute("data-condition-template");
            addButton.hidden = false;
            addButton.addEventListener("click", function () {
                var row = blank.cloneNode(true);
                var list = row.querySelector("datalist");
                var input = row.querySelector(".metadata-value-input");
                added++;
                list.id = "condition-values-added-" + added;
                input.setAttribute("list", list.id);
                container.appendChild(row);
                wireRemove(row);
                wireMetadataValue(row);
                input.focus();
            });
        }
        if (form) {
            form.addEventListener("submit", function (e) {
                if (e.defaultPrevented) { return; }
                var named = Array.prototype.some.call(form.querySelectorAll(".metadata-value-input"),
                    function (input) { return input.value.trim() !== ""; });
                if (!named && !window.confirm(form.getAttribute("data-confirm-every-gallery"))) {
                    e.preventDefault();
                }
            });
        }
    });

    // --- Export CBZ (chapter and series pages) ---
    //     The outline (file names) is fetched when the popup opens, so Export can open the browser's save dialog at
    //     once: browsers allow that only right after a click. The server processes and writes the pages one by one;
    //     the popup follows by an id sent with the download. That id also cancels it, and tells why an export failed
    //     when the browser saves the file itself (Firefox, phones) and shows no reason.
    (function () {
        var link = document.querySelector("[data-export-open]");
        var dialog = document.querySelector("dialog[data-export]");
        if (!link || !dialog || typeof dialog.showModal !== "function") { return; }
        var select = dialog.querySelector("[data-export-workflow]");
        var workflowNote = dialog.querySelector("[data-export-workflow-note]");
        var progress = dialog.querySelector("[data-export-progress]");
        var statusLine = dialog.querySelector("[data-export-status]");
        var startBtn = dialog.querySelector("[data-export-start]");
        var closeBtn = dialog.querySelector("[data-export-close]");
        var progressUrl = dialog.getAttribute("data-progress-url");
        // How long a download the browser started itself may go unseen by the server before the popup stops asking.
        var UNSEEN_LIMIT_MS = 30000;
        var outline = null;
        var job = null;

        function setStatus(text, isError) {
            statusLine.textContent = text || "";
            statusLine.classList.toggle("error", !!isError);
        }

        // total undefined: busy without a known end; null: hidden.
        function setProgress(done, total) {
            progress.hidden = total === null;
            if (total === undefined) {
                progress.removeAttribute("value");
            } else if (total !== null) {
                progress.max = total;
                progress.value = done;
            }
        }

        function bytes(n) {
            var units = ["B", "KB", "MB", "GB"];
            var i = 0;
            while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
            return (i === 0 ? n : n.toFixed(1)) + " " + units[i];
        }

        function errorOf(response, fallback) {
            return response.json()
                .then(function (body) { return (body && (body.error || body.message)) || fallback; },
                    function () { return fallback; })
                .then(function (message) { throw new Error(message); });
        }

        function layout() {
            var checked = dialog.querySelector('input[name="exportLayout"]:checked');
            return checked ? checked.value : null;
        }

        function withParams(url, params) {
            var u = new URL(url, location.href);
            Object.keys(params).forEach(function (key) {
                if (params[key]) { u.searchParams.set(key, params[key]); }
            });
            return u.pathname + u.search;
        }

        function setRunning(running) {
            startBtn.disabled = running || !outline;
            select.disabled = running;
            dialog.querySelectorAll('input[name="exportLayout"]').forEach(function (r) { r.disabled = running; });
            closeBtn.textContent = running ? "Cancel" : "Close";
        }

        function loadOutline() {
            if (outline) { return; }
            setStatus("Reading the pages…");
            fetch(dialog.getAttribute("data-outline-url"), { headers: JSON_ANSWER })
                .then(function (r) { return r.ok ? r.json() : errorOf(r, "The pages could not be read."); })
                .then(function (data) {
                    outline = data;
                    var pages = data.chapters.reduce(function (n, c) { return n + c.pages.length; }, 0);
                    setStatus(pages + " page(s)" + (data.chapters.length > 1 ? " in " + data.chapters.length
                        + " chapters" : "") + ".");
                    setRunning(false);
                })
                .catch(function (e) { setStatus(e.message, true); });
        }

        function loadWorkflows() {
            fetch(dialog.getAttribute("data-workflows-url"), { headers: JSON_ANSWER })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (list) {
                    var usable = list ? list.workflows.filter(function (w) { return w.valid; }) : [];
                    if (list) { fillWorkflowSelect(select, list.workflows, false); }
                    workflowNote.hidden = usable.length > 0;
                    workflowNote.textContent = list && !list.reachable
                        ? "ComfyUI is not answering, so no workflow can be used. See Guide → ComfyUI."
                        : "There is no ComfyUI workflow to use. See Guide → ComfyUI.";
                })
                .catch(function () { /* the select keeps "None" */ });
        }

        function describe(current) {
            var p = current.progress;
            var text = !p || !p.total ? "Starting…"
                : "Page " + Math.min(p.done + 1, p.total) + " of " + p.total + (p.message ? ": " + p.message : "");
            return current.saved ? text + " (" + bytes(current.saved) + " saved)" : text;
        }

        function progressOf(current) {
            return fetch(progressUrl + current.id, { headers: JSON_ANSWER, cache: "no-store" })
                .then(function (r) { return r.ok ? r.json() : null; }, function () { return null; });
        }

        // Asks every second until the export ends; resolves with how it ended, or null when the popup stopped
        // following it (cancelled, finished by the save, or never seen by the server).
        function follow(current) {
            var started = Date.now();
            return new Promise(function (resolve) {
                function poll() {
                    if (current.ended) { resolve(null); return; }
                    progressOf(current).then(function (p) {
                        if (current.ended) { resolve(null); return; }
                        if (p) {
                            current.progress = p;
                            if (p.state !== "RUNNING") { resolve(p); return; }
                            setProgress(p.done, p.total || undefined);
                            setStatus(describe(current));
                        } else if (!current.handle && Date.now() - started > UNSEEN_LIMIT_MS) {
                            resolve(null);
                            return;
                        }
                        setTimeout(poll, 1000);
                    });
                }
                poll();
            });
        }

        // The browser saves the file itself; only the server's progress says how it went.
        function download(current, url) {
            var a = document.createElement("a");
            a.href = url;
            a.download = "";
            document.body.appendChild(a);
            a.click();
            a.remove();
            return follow(current).then(function (p) {
                current.ended = true;
                if (current.cancelled) { return; }
                if (!p) {
                    setStatus("Your browser is saving the file. Its downloads list shows how far it got.");
                } else if (p.state === "DONE") {
                    setStatus("Done. Your browser has saved the file; its downloads list shows where.");
                } else if (p.state === "FAILED") {
                    throw new Error(p.message || "The export failed.");
                }
            });
        }

        // Through the file the save dialog gave, so the popup sees every byte arrive.
        function save(current, url) {
            follow(current);
            return fetch(url, { signal: current.controller.signal })
                .then(function (r) { return r.ok ? r : errorOf(r, "The export failed."); })
                .then(function (r) {
                    return current.handle.createWritable().then(function (writable) {
                        var counter = new TransformStream({
                            transform: function (chunk, controller) {
                                current.saved += chunk.byteLength;
                                setStatus(describe(current));
                                controller.enqueue(chunk);
                            }
                        });
                        // An abort or a cut-off download aborts the file too, so nothing half-written is kept.
                        return r.body.pipeThrough(counter).pipeTo(writable, { signal: current.controller.signal });
                    });
                })
                .then(function () {
                    current.ended = true;
                    setStatus("Saved " + current.handle.name + " (" + bytes(current.saved) + ").");
                }, function (e) {
                    if (current.cancelled) { throw e; }
                    // A download cut off says nothing; the server's progress says why.
                    return progressOf(current).then(function (p) {
                        throw new Error(p && p.state === "FAILED" && p.message ? p.message : e.message);
                    });
                });
        }

        function sendCancel(current) {
            var form = new FormData();
            if (csrfParam) { form.append(csrfParam, csrfToken); }
            var url = progressUrl + current.id + "/cancel";
            if (!(navigator.sendBeacon && navigator.sendBeacon(url, form))) {
                fetch(url, { method: "POST", body: form, keepalive: true }).catch(function () {});
            }
        }

        function start() {
            if (job || !outline) { return; }
            var chosenLayout = layout();
            var name = chosenLayout === "CHAPTERS" ? outline.zipName : outline.cbzName;
            var current = {
                id: "export-" + Math.random().toString(36).slice(2) + Date.now().toString(36),
                handle: null,
                progress: null,
                saved: 0,
                cancelled: false,
                ended: false,
                controller: new AbortController()
            };
            var url = withParams(dialog.getAttribute("data-download-url"),
                { workflow: select.value, layout: chosenLayout, "export": current.id });
            job = current;
            setRunning(true);
            var picking = window.showSaveFilePicker
                ? window.showSaveFilePicker({
                    suggestedName: name,
                    types: [{
                        description: chosenLayout === "CHAPTERS" ? "ZIP file" : "CBZ file",
                        accept: chosenLayout === "CHAPTERS"
                            ? { "application/zip": [".zip"] }
                            : { "application/vnd.comicbook+zip": [".cbz"] }
                    }]
                })
                : Promise.resolve(null);
            picking
                .then(function (handle) { return { handle: handle }; },
                    function (e) { return e && e.name === "AbortError" ? null : { handle: null }; })
                .then(function (picked) {
                    if (!picked) {
                        setStatus("");
                        return;
                    }
                    current.handle = picked.handle;
                    setProgress(0, undefined);
                    setStatus("Starting…");
                    return current.handle ? save(current, url) : download(current, url);
                })
                .catch(function (e) {
                    current.ended = true;
                    setStatus(current.cancelled ? "Cancelled." : e.message, !current.cancelled);
                })
                .then(function () {
                    current.ended = true;
                    setProgress(0, null);
                    if (job === current) { job = null; }
                    setRunning(false);
                });
        }

        function cancel() {
            if (!job) { return; }
            job.cancelled = true;
            job.ended = true;
            job.controller.abort();
            sendCancel(job);
            setStatus("Cancelled.");
        }

        link.addEventListener("click", function (e) {
            e.preventDefault();
            dialog.showModal();
            loadOutline();
            loadWorkflows();
        });
        startBtn.addEventListener("click", start);
        closeBtn.addEventListener("click", function () {
            if (job) { cancel(); } else { dialog.close(); }
        });
        // Escape would close the popup and hide a running export; Cancel stops it.
        dialog.addEventListener("cancel", function (e) {
            if (job) { e.preventDefault(); }
        });
        // Leaving the page ends a download the popup saves, which the server notices only at its next page. A file
        // the browser saves itself carries on.
        window.addEventListener("pagehide", function () {
            if (job && job.handle) { sendCancel(job); }
        });
    })();

    // --- Does this browser display JPEG XL? ---
    //     The answer goes in a cookie that tells the server whether to decode .jxl pages to PNG. Probed on
    //     every load, since a browser update can change support and the probe costs nothing.
    (function () {
        var probe = new Image();
        function record(supported) {
            try {
                document.cookie = "jxl=" + (supported ? "1" : "0") + "; path=/; max-age=31536000; SameSite=Lax";
            } catch (e) {
                // Cookies blocked: the server keeps decoding to PNG, which still works.
            }
        }
        probe.onload = function () { record(probe.naturalWidth === 2); };
        probe.onerror = function () { record(false); };
        probe.src = "data:image/jxl;base64,/woIEBAJCAIBAJgCSxibnHGEAziAAzggSsA5BQEAIESACBABIkDk/5F7+h5aZ1dVVVUlSZIQUHd3d3d3////v1VvZmZmBv7fv+e/h8acc661z71JkiQEVFVVVVVV////z72vu7u7G/7fv+e/h8acc661z71JkiQEVFVVVVVV////z72vu7u7G/7fv+e/h8acc661z71JkiQEVFVVVVVV////z72vu7u7+wIhAHj4e/RjAA==";
    })();
})();
