/*
  Line numbers and "go to the error" for the JSON editors (every textarea.json-editor). Plain JavaScript, no
  library: the editors stay ordinary textareas, so forms and htmx submit them exactly as before.

  - A gutter of line numbers sits beside each editor and scrolls with it. Lines don't wrap (wrap="off"), so
    one line of text is always one numbered row.
  - Under each editor, "Line X, column Y" shows where the cursor is.
  - In any element with data-json-editor="<textarea id>", text like "Line 906, column 9" becomes a button that
    selects that spot in the editor and scrolls to it. Server error messages use exactly that wording
    (JsonText.JsonSyntaxException.describe).
*/
(() => {
    "use strict";

    const LOCATION = /Line (\d+), column (\d+)/;

    function enhance(textarea) {
        if (textarea.dataset.lineNumbers) {
            return;
        }
        textarea.dataset.lineNumbers = "on";
        textarea.setAttribute("wrap", "off");

        // After an htmx out-of-band swap the new textarea lands inside the old wrapper: reuse it.
        let wrap = textarea.parentElement;
        if (!wrap.classList.contains("json-editor-wrap")) {
            wrap = document.createElement("div");
            wrap.className = "json-editor-wrap";
            textarea.before(wrap);
            const gutter = document.createElement("pre");
            gutter.className = "json-gutter";
            gutter.setAttribute("aria-hidden", "true");
            wrap.append(gutter, textarea);
            const position = document.createElement("div");
            position.className = "json-editor-pos small muted";
            wrap.after(position);
        }
        const gutter = wrap.querySelector(".json-gutter");
        const position = wrap.nextElementSibling;

        let rows = 0;
        const renumber = () => {
            const count = textarea.value.split("\n").length;
            if (count !== rows) {
                rows = count;
                gutter.textContent = Array.from({length: count}, (_, i) => i + 1).join("\n");
                gutter.style.width = `calc(${Math.max(String(count).length, 2)}ch + 16px)`; // room for the widest number
            }
            gutter.scrollTop = textarea.scrollTop;
        };
        const showPosition = () => {
            const before = textarea.value.slice(0, textarea.selectionStart).split("\n");
            position.textContent = `Line ${before.length}, column ${before[before.length - 1].length + 1}`;
        };

        textarea.addEventListener("input", renumber);
        textarea.addEventListener("scroll", () => { gutter.scrollTop = textarea.scrollTop; });
        for (const event of ["keyup", "click", "select", "focus"]) {
            textarea.addEventListener(event, showPosition);
        }
        renumber();
        showPosition();
    }

    /** Selects the character at line/column (1-based) and scrolls it into view. */
    function jumpTo(textarea, line, column) {
        const lines = textarea.value.split("\n");
        const row = Math.min(Math.max(line, 1), lines.length);
        let start = 0;
        for (let i = 0; i < row - 1; i++) {
            start += lines[i].length + 1;
        }
        const offset = start + Math.min(Math.max(column - 1, 0), lines[row - 1].length);
        const details = textarea.closest("details");
        if (details) {
            details.open = true;
        }
        textarea.focus();
        textarea.setSelectionRange(offset, Math.min(offset + 1, start + lines[row - 1].length));

        // Browsers don't reliably scroll a textarea to its selection, so do it by hand.
        const style = getComputedStyle(textarea);
        const lineHeight = parseFloat(style.lineHeight);
        textarea.scrollTop = Math.max(0, (row - 1) * lineHeight - textarea.clientHeight / 3);
        const charWidth = measureChar(style);
        textarea.scrollLeft = Math.max(0, (column - 1) * charWidth - textarea.clientWidth / 2);
        textarea.dispatchEvent(new Event("scroll"));
        textarea.dispatchEvent(new Event("select"));
    }

    function measureChar(style) {
        const context = document.createElement("canvas").getContext("2d");
        context.font = `${style.fontSize} ${style.fontFamily}`;
        return context.measureText("0").width;
    }

    /** Turns "Line N, column M" inside [data-json-editor] elements into buttons that jump there. */
    function linkLocations(root) {
        const boxes = root.matches?.("[data-json-editor]") ? [root] : [];
        boxes.push(...root.querySelectorAll("[data-json-editor]"));
        for (const box of boxes) {
            const textarea = document.getElementById(box.dataset.jsonEditor);
            if (!textarea) {
                continue;
            }
            const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT);
            const texts = [];
            while (walker.nextNode()) {
                texts.push(walker.currentNode);
            }
            for (const text of texts) {
                const match = LOCATION.exec(text.nodeValue);
                if (!match || text.parentElement.closest("button")) {
                    continue;
                }
                const location = text.splitText(match.index);
                location.splitText(match[0].length);
                const button = document.createElement("button");
                button.type = "button";
                button.className = "link-button";
                button.textContent = match[0];
                button.title = "Show this place in the editor";
                button.addEventListener("click", () => jumpTo(textarea, Number(match[1]), Number(match[2])));
                location.replaceWith(button);
            }
        }
    }

    function init(root) {
        root.querySelectorAll("textarea.json-editor").forEach(enhance);
        linkLocations(root);
    }

    document.addEventListener("DOMContentLoaded", () => init(document.body));
    // htmx swaps in new content (validation results, a re-rendered editor): enhance that too.
    document.addEventListener("htmx:load", event => init(event.detail.elt));
})();
