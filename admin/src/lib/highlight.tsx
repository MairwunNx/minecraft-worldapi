import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import { type Language, StreamLanguage, type StreamParser } from "@codemirror/language";
import { properties } from "@codemirror/legacy-modes/mode/properties";
import { toml } from "@codemirror/legacy-modes/mode/toml";
import { Tag, highlightTree, tagHighlighter, tags } from "@lezer/highlight";
import type { ReactNode } from "react";

/** Tags for server logs, which no CodeMirror language knows. */
const logTags = { time: Tag.define(), warn: Tag.define(), error: Tag.define() };

/** Paper's console lines (`[12:00:00 WARN]: …`) and latest.log's (`[12:00:00] [Server thread/WARN]: …`); a stack trace keeps its line's level. */
const logParser: StreamParser<{ level: string }> = {
  name: "log",
  startState: () => ({ level: "INFO" }),
  token(stream, state) {
    if (stream.sol()) {
      const match = stream.match(/^\[(?:\d{2}:\d{2}:\d{2}) (\w+)\]:?|^\[(?:\d{2}:\d{2}:\d{2})\] \[[^\]]*\/(\w+)\]:?/) as RegExpMatchArray | null;
      if (match) {
        state.level = (match[1] ?? match[2] ?? "INFO").toUpperCase();
        return "time";
      }
    }
    stream.skipToEnd();
    if (state.level === "WARN" || state.level === "WARNING") return "warn";
    if (state.level === "ERROR" || state.level === "FATAL" || state.level === "SEVERE") return "error";
    return null;
  },
  tokenTable: logTags,
};

const languages = {
  yaml: yaml().language,
  json: json().language,
  properties: StreamLanguage.define(properties),
  toml: StreamLanguage.define(toml),
  log: StreamLanguage.define(logParser),
};

/** The language for a file by its name; null for plain text. */
export const languageFor = (path: string): Language | null => {
  const name = path.toLowerCase().replace(/\.gz$/, "");
  if (name.endsWith(".yml") || name.endsWith(".yaml")) return languages.yaml;
  if (name.endsWith(".json") || name.endsWith(".mcmeta") || name.endsWith(".json5")) return languages.json;
  if (name.endsWith(".toml")) return languages.toml;
  if (name.endsWith(".properties") || name.endsWith(".conf") || name.endsWith(".cfg") || name.endsWith(".ini") || name.endsWith(".txt") && name.includes("eula")) return languages.properties;
  if (name.endsWith(".log") || name.startsWith("logs/")) return languages.log;
  return null;
};

/** Classes, not inline colors: index.css gives them the chart tokens in both themes. */
export const highlighter = tagHighlighter([
  { tag: [tags.propertyName, tags.definition(tags.propertyName), tags.attributeName, tags.definition(tags.variableName)], class: "tok-key" },
  { tag: [tags.string, tags.special(tags.string), tags.content, tags.quote], class: "tok-string" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], class: "tok-value" },
  { tag: [tags.keyword, tags.operator, tags.definitionOperator], class: "tok-keyword" },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], class: "tok-comment" },
  { tag: [tags.meta, tags.labelName, tags.heading], class: "tok-meta" },
  { tag: logTags.time, class: "tok-time" },
  { tag: logTags.warn, class: "tok-warn" },
  { tag: logTags.error, class: "tok-error" },
]);

/** Highlights the text as one document and hands back each line's spans, for the numbered viewer. */
export const highlightLines = (path: string, lines: string[]): ReactNode[] => {
  const language = languageFor(path);
  if (!language || !lines.length) return lines;
  const text = lines.join("\n");
  const ranges: Array<[number, number, string]> = [];
  try {
    highlightTree(language.parser.parse(text), highlighter, (from, to, classes) => ranges.push([from, to, classes]));
  } catch {
    return lines;
  }
  const result: ReactNode[] = [];
  let start = 0;
  let range = 0;
  for (const line of lines) {
    const end = start + line.length;
    const parts: ReactNode[] = [];
    let at = start;
    while (range < ranges.length && ranges[range][0] < end) {
      const [from, to, classes] = ranges[range];
      if (to <= at) {
        range++;
        continue;
      }
      const a = Math.max(from, at);
      const b = Math.min(to, end);
      if (a > at) parts.push(text.slice(at, a));
      parts.push(
        <span key={a} className={classes}>
          {text.slice(a, b)}
        </span>,
      );
      at = b;
      if (to <= end) range++;
      else break;
    }
    if (at < end) parts.push(text.slice(at, end));
    result.push(parts);
    start = end + 1;
  }
  return result;
};
