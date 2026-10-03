// Tokenized autocomplete input: chips backed by hidden inputs named data-name.
// With data-excluded-name, a click toggles a chip to "exclude" by renaming its hidden input, so the
// server gets two plain id lists and no chip carries state of its own.
(function () {
    "use strict";

    function debounce(fn, ms) {
        var t;
        return function () {
            var args = arguments, self = this;
            clearTimeout(t);
            t = setTimeout(function () { fn.apply(self, args); }, ms);
        };
    }

    function setupField(field) {
        var type = field.getAttribute("data-type");
        // The Manage page offers only what its actions take (no tag versions), so it names its own endpoint.
        var source = field.getAttribute("data-source") || "/api/autocomplete/" + encodeURIComponent(type);
        var name = field.getAttribute("data-name");
        var valueField = field.getAttribute("data-value-field") || "id";
        var max = parseInt(field.getAttribute("data-max") || "0", 10); // 0 = unlimited
        var excludedName = field.getAttribute("data-excluded-name");
        var tokens = field.querySelector(".tokens");
        var input = field.querySelector(".token-input");
        var dropdown = field.querySelector(".token-dropdown");
        var selected = new Set();
        var activeIndex = -1;

        function refreshSelectedSet() {
            selected.clear();
            tokens.querySelectorAll('input[type=hidden]').forEach(function (h) { selected.add(h.value); });
        }

        function wireRemove(token) {
            token.querySelector(".token-remove").addEventListener("click", function () {
                token.parentNode.removeChild(token);
                refreshSelectedSet();
            });
        }

        function setExcluded(token, excluded) {
            token.classList.toggle("is-excluded", excluded);
            token.querySelector("input[type=hidden]").name = excluded ? excludedName : name;
            token.querySelector(".token-mode").textContent = excluded ? "\u2212" : "+";
            token.querySelector(".token-toggle").title =
                excluded ? "Excluded - click to include" : "Included - click to exclude";
        }

        // On the whole chip so a click on its padding counts too; the button is for keyboard users.
        function wireToggle(token) {
            token.addEventListener("click", function (e) {
                if (e.target.closest(".token-remove")) { return; }
                setExcluded(token, !token.classList.contains("is-excluded"));
            });
        }

        function wireToken(token) {
            wireRemove(token);
            if (excludedName) { wireToggle(token); }
        }

        tokens.querySelectorAll(".token").forEach(wireToken);
        refreshSelectedSet();

        var clearBtn = field.parentElement ? field.parentElement.querySelector(".token-clear") : null;
        if (clearBtn) {
            clearBtn.addEventListener("click", function () {
                tokens.querySelectorAll(".token").forEach(function (t) { t.parentNode.removeChild(t); });
                selected.clear();
                closeDropdown();
            });
        }

        function addToken(value, label) {
            value = String(value);
            if (selected.has(value)) { return; }
            if (max === 1) {
                tokens.querySelectorAll(".token").forEach(function (t) { t.parentNode.removeChild(t); });
                selected.clear();
            }
            var span = document.createElement("span");
            span.className = "token";
            var lbl = document.createElement("span");
            lbl.className = "token-label";
            lbl.textContent = label;
            var btn = document.createElement("button");
            btn.type = "button";
            btn.className = "token-remove";
            btn.setAttribute("aria-label", "Remove");
            btn.textContent = "×";
            var hidden = document.createElement("input");
            hidden.type = "hidden";
            hidden.name = name;
            hidden.value = value;
            if (excludedName) {
                // Must match the searchToken fragment's markup.
                span.className = "token token-toggleable";
                var toggle = document.createElement("button");
                toggle.type = "button";
                toggle.className = "token-toggle";
                var mode = document.createElement("span");
                mode.className = "token-mode";
                mode.setAttribute("aria-hidden", "true");
                toggle.appendChild(mode);
                toggle.appendChild(lbl);
                span.appendChild(toggle);
            } else {
                span.appendChild(lbl);
            }
            span.appendChild(btn);
            span.appendChild(hidden);
            tokens.appendChild(span);
            selected.add(value);
            wireToken(span);
            if (excludedName) { setExcluded(span, false); }
        }

        function closeDropdown() {
            dropdown.hidden = true;
            dropdown.innerHTML = "";
            activeIndex = -1;
        }

        function renderOptions(options) {
            dropdown.innerHTML = "";
            var shown = options.filter(function (o) {
                var v = valueField === "label" ? o.label : o.id;
                return !selected.has(String(v));
            });
            if (shown.length === 0) { closeDropdown(); return; }
            shown.forEach(function (o) {
                var div = document.createElement("div");
                div.className = "token-option";
                div.textContent = o.label;
                div.addEventListener("mousedown", function (e) {
                    e.preventDefault();
                    addToken(valueField === "label" ? o.label : o.id, o.label);
                    input.value = "";
                    closeDropdown();
                });
                dropdown.appendChild(div);
            });
            dropdown.hidden = false;
            activeIndex = -1;
        }

        var fetchOptions = debounce(function () {
            var q = input.value.trim();
            fetch(source + (source.indexOf("?") < 0 ? "?" : "&") + "q=" + encodeURIComponent(q))
                .then(function (r) { return r.ok ? r.json() : []; })
                .then(renderOptions)
                .catch(function () { closeDropdown(); });
        }, 180);

        input.addEventListener("input", fetchOptions);
        input.addEventListener("focus", fetchOptions);

        input.addEventListener("keydown", function (e) {
            var opts = dropdown.querySelectorAll(".token-option");
            if (e.key === "ArrowDown") {
                e.preventDefault();
                activeIndex = Math.min(activeIndex + 1, opts.length - 1);
            } else if (e.key === "ArrowUp") {
                e.preventDefault();
                activeIndex = Math.max(activeIndex - 1, 0);
            } else if (e.key === "Enter") {
                if (!dropdown.hidden && activeIndex >= 0 && opts[activeIndex]) {
                    e.preventDefault();
                    opts[activeIndex].dispatchEvent(new MouseEvent("mousedown"));
                }
                return;
            } else if (e.key === "Escape") {
                closeDropdown();
                return;
            } else {
                return;
            }
            opts.forEach(function (o, i) { o.classList.toggle("active", i === activeIndex); });
        });

        document.addEventListener("click", function (e) {
            if (!field.contains(e.target)) { closeDropdown(); }
        });
    }

    document.querySelectorAll(".token-field").forEach(setupField);
})();
