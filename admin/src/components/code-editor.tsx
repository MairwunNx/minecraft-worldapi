import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, indentOnInput, syntaxHighlighting } from "@codemirror/language";
import { highlightSelectionMatches, search, searchKeymap } from "@codemirror/search";
import { EditorState } from "@codemirror/state";
import { EditorView, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers } from "@codemirror/view";
import { useEffect, useRef } from "react";
import { highlighter, languageFor } from "@/lib/highlight";

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
          syntaxHighlighting(highlighter),
          keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab]),
          EditorView.contentAttributes.of({ "aria-label": label, spellcheck: "false" }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) changed.current(update.state.doc.toString());
          }),
          languageFor(path)?.extension ?? [],
        ],
      }),
    });
    return () => view.destroy();
    // The editor owns its text after the start; a new file mounts a new editor.
  }, [path]);
  return <div ref={host} className="h-[65dvh] min-h-72 overflow-hidden rounded-lg border" />;
};
