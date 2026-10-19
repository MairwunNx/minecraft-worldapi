import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import { HighlightStyle, StreamLanguage, bracketMatching, indentOnInput, syntaxHighlighting } from "@codemirror/language";
import { properties } from "@codemirror/legacy-modes/mode/properties";
import { highlightSelectionMatches, search, searchKeymap } from "@codemirror/search";
import { EditorState, type Extension } from "@codemirror/state";
import { EditorView, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { useEffect, useRef } from "react";

/** Syntax colors from the chart tokens, so both themes stay readable. */
const highlight = HighlightStyle.define([
  { tag: [tags.propertyName, tags.definition(tags.propertyName)], color: "var(--chart-1)" },
  { tag: [tags.string, tags.special(tags.string)], color: "var(--chart-3)" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], color: "var(--chart-2)" },
  { tag: [tags.keyword, tags.operator], color: "var(--chart-5)" },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: "var(--muted-foreground)", fontStyle: "italic" },
  { tag: [tags.meta, tags.labelName], color: "var(--chart-4)" },
]);

const language = (path: string): Extension[] => {
  const name = path.toLowerCase();
  if (name.endsWith(".yml") || name.endsWith(".yaml")) return [yaml()];
  if (name.endsWith(".json") || name.endsWith(".mcmeta")) return [json()];
  if (name.endsWith(".properties") || name.endsWith(".conf") || name.endsWith(".cfg") || name.endsWith(".ini")) return [StreamLanguage.define(properties)];
  return [];
};

/** CodeMirror 6 for configs. It styles itself through style elements, which the panel's CSP allows. */
export const CodeEditor = ({ path, value, onChange, label }: { path: string; value: string; onChange: (value: string) => void; label: string }) => {
  const host = useRef<HTMLDivElement>(null);
  const changed = useRef(onChange);
  changed.current = onChange;
  useEffect(() => {
    if (!host.current) return;
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightActiveLine(),
          history(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          search({ top: true }),
          highlightSelectionMatches(),
          syntaxHighlighting(highlight),
          keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab]),
          EditorView.contentAttributes.of({ "aria-label": label, spellcheck: "false" }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) changed.current(update.state.doc.toString());
          }),
          ...language(path),
        ],
      }),
    });
    return () => view.destroy();
    // The editor owns its text after the start; a new file mounts a new editor.
  }, [path]);
  return <div ref={host} className="h-[65dvh] min-h-72 overflow-hidden rounded-lg border" />;
};
