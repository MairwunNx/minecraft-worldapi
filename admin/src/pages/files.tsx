import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { structuredPatch } from "diff";
import { ArrowLeft, ChevronRight, File, FileLock, Folder, FolderOpen, Pencil, RefreshCw, Search, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ApiError, client, data, type Schema } from "@/api/client";
import { CodeEditor } from "@/components/code-editor";
import { Empty, ErrorBox, Loading, PageHeader } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { bytes, dateTime, number } from "@/lib/format";
import { errorText, showError } from "@/lib/forms";
import { useCan } from "@/lib/session";
import { cn } from "@/lib/utils";

type Entry = Schema<"FileEntry">;

const parent = (path: string) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "");
const ancestors = (path: string) => path.split("/").slice(0, -1).map((_, index, parts) => parts.slice(0, index + 1).join("/"));

const folderQuery = (path: string) => ({
  queryKey: ["files", "list", path],
  queryFn: () => data(client.GET("/files", { params: { query: { path: path || undefined } } })),
  staleTime: 10_000,
});

/** One folder's entries, folders first as WorldAPI sends them; folders open in place. */
const FolderNode = ({ path, depth, open, toggle, selected }: { path: string; depth: number; open: Set<string>; toggle: (path: string) => void; selected?: string }) => {
  const { t } = useTranslation();
  const query = useQuery(folderQuery(path));
  if (query.error) return <div className="px-2 py-1 text-xs text-destructive" style={{ paddingLeft: depth * 12 + 8 }}>{errorText(query.error)}</div>;
  if (!query.data)
    return (
      <div className="px-2 py-1 text-xs text-muted-foreground" style={{ paddingLeft: depth * 12 + 8 }}>
        {t("common.loading")}
      </div>
    );
  return (
    <ul role={depth ? "group" : "tree"} aria-label={depth ? undefined : t("files.tree")}>
      {query.data.entries.map((entry) => {
        const expanded = open.has(entry.path);
        const Icon = entry.directory ? (expanded ? FolderOpen : Folder) : entry.readable ? File : FileLock;
        const row = cn(
          "flex w-full min-w-0 items-center gap-1.5 rounded-md py-1 pr-2 text-left text-sm hover:bg-accent",
          selected === entry.path && "bg-accent font-medium",
          !entry.readable && !entry.directory && "text-muted-foreground",
        );
        return (
          <li key={entry.path} role="treeitem" aria-expanded={entry.directory ? expanded : undefined} aria-selected={selected === entry.path}>
            {entry.directory ? (
              <button type="button" className={row} style={{ paddingLeft: depth * 12 + 4 }} onClick={() => toggle(entry.path)}>
                <ChevronRight className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", expanded && "rotate-90")} aria-hidden />
                <Icon className="size-4 shrink-0 text-chart-4" aria-hidden />
                <span className="truncate">{entry.name}</span>
              </button>
            ) : (
              <Link to="/files" search={{ file: entry.path }} className={row} style={{ paddingLeft: depth * 12 + 22 }} disabled={!entry.readable}>
                <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="truncate">{entry.name}</span>
              </Link>
            )}
            {entry.directory && expanded && <FolderNode path={entry.path} depth={depth + 1} open={open} toggle={toggle} selected={selected} />}
          </li>
        );
      })}
      {query.data.truncated && <li className="px-2 py-1 text-xs text-muted-foreground">{t("files.truncated")}</li>}
      {!query.data.entries.length && (
        <li className="py-1 text-xs text-muted-foreground" style={{ paddingLeft: depth * 12 + 22 }}>
          {t("files.emptyFolder")}
        </li>
      )}
    </ul>
  );
};

interface Line {
  number: number | null;
  text: string;
}

/** WorldAPI numbers lines as `12\ttext`, and marks skipped stretches between grep matches with `--`. */
const parseLines = (content: string): Line[] =>
  content
    ? content.replace(/\n$/, "").split("\n").map((line) => {
        if (line === "--") return { number: null, text: "" };
        const tab = line.indexOf("\t");
        return { number: Number(line.slice(0, tab)), text: line.slice(tab + 1) };
      })
    : [];

const Lines = ({ lines, grep }: { lines: Line[]; grep?: string }) => {
  const needle = grep?.toLowerCase();
  return (
    <div className="overflow-auto rounded-lg border bg-card font-mono text-xs leading-relaxed">
      <table className="w-full border-collapse">
        <tbody>
          {lines.map((line, index) =>
            line.number === null ? (
              <tr key={`gap-${index}`}>
                <td colSpan={2} className="border-y border-dashed bg-muted/50 py-0.5 text-center text-muted-foreground select-none">
                  ⋯
                </td>
              </tr>
            ) : (
              <tr key={line.number} className={cn(needle && line.text.toLowerCase().includes(needle) && "bg-warning/15")}>
                <td className="tabular w-px border-r bg-muted/40 px-2 text-right align-top whitespace-nowrap text-muted-foreground select-none">{line.number}</td>
                <td className="px-3 break-all whitespace-pre-wrap">{line.text || " "}</td>
              </tr>
            ),
          )}
        </tbody>
      </table>
    </div>
  );
};

/** Pages of 500 numbered lines, or matching lines with two around each, or the last 500 lines. */
const Viewer = ({ path, entry, onEdit }: { path: string; entry?: Entry; onEdit?: (sha: string) => void }) => {
  const { t } = useTranslation();
  const [grepInput, setGrepInput] = useState("");
  const [grep, setGrep] = useState("");
  const [tail, setTail] = useState(path.startsWith("logs/"));
  const query = useInfiniteQuery({
    queryKey: ["files", "read", path, grep, tail],
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      data(
        client.GET("/files/read", {
          params: { query: grep ? { path, grep, context: 2, limit: 200, offset: pageParam } : tail ? { path, tail: 500 } : { path, offset: pageParam, limit: 500 } },
        }),
      ),
    getNextPageParam: (page) => (tail && !grep ? undefined : (page.nextOffset ?? undefined)),
  });
  const first = query.data?.pages[0];
  const lines = query.data?.pages.flatMap((page) => parseLines(page.content)) ?? [];
  const cut = query.data?.pages.reduce((sum, page) => sum + (page.cutLines ?? 0), 0) ?? 0;
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        {first && (
          <>
            <span>{t("files.lines", { count: first.totalLines })}</span>
            <span>·</span>
            <span>{bytes(first.size)}</span>
            <span>·</span>
            <span>{t("files.modified", { when: dateTime(first.modified) })}</span>
          </>
        )}
        {entry && !entry.writable && <Badge variant="outline">{t("files.readOnly")}</Badge>}
      </div>
      <div className="flex flex-wrap gap-2">
        <form
          className="flex min-w-0 flex-1 basis-56 gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            setGrep(grepInput.trim());
          }}
        >
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground" aria-hidden />
            <Input className="pl-8" placeholder={t("files.grep")} aria-label={t("files.grep")} value={grepInput} onChange={(event) => setGrepInput(event.target.value)} />
          </div>
          {grep && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("files.clearGrep")}
              onClick={() => {
                setGrep("");
                setGrepInput("");
              }}
            >
              <X className="size-4" />
            </Button>
          )}
        </form>
        <Button variant={tail ? "secondary" : "outline"} size="sm" aria-pressed={tail} onClick={() => setTail(!tail)} disabled={!!grep}>
          {t("files.tail")}
        </Button>
        <Button variant="outline" size="icon" aria-label={t("files.reload")} title={t("files.reload")} onClick={() => query.refetch()}>
          <RefreshCw className="size-4" />
        </Button>
        {onEdit && first?.sha256 && (
          <Button size="sm" onClick={() => onEdit(first.sha256!)}>
            <Pencil className="size-4" aria-hidden />
            {t("files.edit")}
          </Button>
        )}
      </div>
      {grep && first?.matches != null && <p className="text-xs text-muted-foreground">{t("files.matches", { count: first.matches })}</p>}
      {cut > 0 && <p className="text-xs text-warning">{t("files.cut", { count: cut })}</p>}
      {query.error ? <ErrorBox error={query.error} /> : query.isPending ? <Loading rows={10} /> : lines.length ? <Lines lines={lines} grep={grep} /> : <Empty>{grep ? t("files.noMatches") : t("files.emptyFile")}</Empty>}
      {query.hasNextPage && (
        <Button variant="outline" size="sm" className="self-center" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          {t("files.more")}
        </Button>
      )}
    </div>
  );
};

/** Requests carry old and new text, so the panel edits files up to about this size. */
const MAX_EDIT = 400_000;

/** The whole file as text, read in pages of 2000 lines; refuses files with cut lines or too large to send back. */
const loadText = async (path: string) => {
  const lines: string[] = [];
  let offset: number | undefined = 1;
  let sha: string | undefined;
  while (offset !== undefined) {
    const page: Schema<"FileContent"> = await data(client.GET("/files/read", { params: { query: { path, offset, limit: 2000 } } }));
    if (page.size > MAX_EDIT) throw new Error("too_large");
    if (page.cutLines) throw new Error("cut");
    if (sha && page.sha256 !== sha) throw new Error("changed");
    sha = page.sha256 ?? undefined;
    lines.push(...parseLines(page.content).map((line) => line.text));
    offset = page.nextOffset ?? undefined;
  }
  return { text: lines.join("\n"), sha: sha ?? "" };
};

const Diff = ({ path, before, after }: { path: string; before: string; after: string }) => {
  const { t } = useTranslation();
  // Both end with a line break here, so a line added at the end doesn't show the old last line as changed.
  const patch = structuredPatch(path, path, `${before}\n`, `${after}\n`, undefined, undefined, { context: 3 });
  if (!patch.hunks.length) return <Empty>{t("files.noChanges")}</Empty>;
  return (
    <div className="max-h-[55dvh] overflow-auto rounded-lg border bg-card font-mono text-xs leading-relaxed">
      {patch.hunks.map((hunk) => {
        let old = hunk.oldStart;
        let next = hunk.newStart;
        return (
          <table key={`${hunk.oldStart}-${hunk.newStart}`} className="w-full border-collapse">
            <tbody>
              <tr>
                <td colSpan={3} className="bg-muted/60 px-2 py-0.5 text-muted-foreground">
                  @@ −{hunk.oldStart},{hunk.oldLines} +{hunk.newStart},{hunk.newLines} @@
                </td>
              </tr>
              {hunk.lines
                .filter((line) => !line.startsWith("\\"))
                .map((line, index) => {
                  const sign = line[0];
                  const numbers = sign === "-" ? [old++, ""] : sign === "+" ? ["", next++] : [old++, next++];
                  return (
                    <tr key={index} className={cn(sign === "-" && "bg-destructive/10", sign === "+" && "bg-success/15")}>
                      <td className="tabular w-px px-1.5 text-right whitespace-nowrap text-muted-foreground select-none">{numbers[0]}</td>
                      <td className="tabular w-px border-r px-1.5 text-right whitespace-nowrap text-muted-foreground select-none">{numbers[1]}</td>
                      <td className="px-2 break-all whitespace-pre-wrap">
                        <span className="select-none" aria-hidden>
                          {sign === " " ? " " : sign}{" "}
                        </span>
                        <span className="sr-only">{sign === "-" ? t("files.removed") : sign === "+" ? t("files.added") : ""}</span>
                        {line.slice(1) || " "}
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        );
      })}
    </div>
  );
};

/**
 * Edits the whole text, then shows the diff before saving. The save replaces the old text with the
 * new through POST /files/edit, so the file keeps its last line break, and sends the sha256 it read:
 * if someone changed the file meanwhile, WorldAPI answers 409 and nothing is lost.
 */
const Editor = ({ path, onClose }: { path: string; onClose: () => void }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const loaded = useQuery({ queryKey: ["files", "edit", path], queryFn: () => loadText(path), staleTime: Infinity, gcTime: 0, retry: false });
  const [text, setText] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (loaded.data) setText(loaded.data.text);
  }, [loaded.data]);
  if (loaded.error) {
    const reason = loaded.error instanceof ApiError ? errorText(loaded.error) : t(`files.cannotEdit.${loaded.error.message}`, { defaultValue: errorText(loaded.error) });
    return (
      <div className="flex flex-col gap-3">
        <ErrorBox error={new ApiError(0, "edit", reason)} />
        <Button variant="outline" className="self-start" onClick={onClose}>
          {t("common.back")}
        </Button>
      </div>
    );
  }
  if (!loaded.data || text === null) return <Loading rows={10} />;
  const original = loaded.data;
  const save = async () => {
    setSaving(true);
    try {
      const result = original.text
        ? await data(client.POST("/files/edit", { body: { path, edits: [{ oldText: original.text, newText: text }], expectedSha256: original.sha } }))
        : await data(client.PUT("/files/write", { body: { path, content: text, expectedSha256: original.sha } }));
      toast.success(t("files.saved", { added: number(result.added), removed: number(result.removed) }), { description: result.note });
      await queryClient.invalidateQueries({ queryKey: ["files"] });
      onClose();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) toast.error(t("files.conflict"));
      else showError(error);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className="flex flex-col gap-3">
      <CodeEditor path={path} value={original.text} onChange={setText} label={t("files.editorLabel", { path })} />
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="outline" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button onClick={() => setReviewing(true)} disabled={text === original.text}>
          {t("files.review")}
        </Button>
      </div>
      <Dialog open={reviewing} onOpenChange={setReviewing}>
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{t("files.reviewTitle")}</DialogTitle>
            <DialogDescription className="break-all">{path}</DialogDescription>
          </DialogHeader>
          <Diff path={path} before={original.text} after={text} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setReviewing(false)} disabled={saving}>
              {t("files.keepEditing")}
            </Button>
            <Button onClick={save} disabled={saving}>
              {t("files.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export const FilesPage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const { file } = useSearch({ from: "/app/files" });
  const navigate = useNavigate({ from: "/files" });
  const [open, setOpen] = useState<Set<string>>(() => new Set(file ? ancestors(file) : []));
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    setEditing(false);
    if (file) setOpen((current) => new Set([...current, ...ancestors(file)]));
  }, [file]);
  const toggle = (path: string) =>
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  const listing = useQuery({ ...folderQuery(file ? parent(file) : ""), enabled: !!file });
  const entry = listing.data?.entries.find((item) => item.path === file);
  const writable = can("files:write") && !!entry?.writable && !file?.endsWith(".gz");
  if (!can("files:read") && !can("logs:read")) return <Empty>{t("files.noAccess")}</Empty>;
  return (
    <div>
      <PageHeader title={t("files.title")} description={can("files:read") ? t("files.text") : t("files.logsOnly")} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <aside className={cn("min-w-0 rounded-lg border bg-card p-1.5 lg:sticky lg:top-6 lg:max-h-[calc(100dvh-8rem)] lg:overflow-auto", file && "hidden lg:block")}>
          <FolderNode path="" depth={0} open={open} toggle={toggle} selected={file} />
        </aside>
        <section className={cn("min-w-0", !file && "hidden lg:block")}>
          {file ? (
            <div className="flex flex-col gap-3">
              <div className="flex min-w-0 items-center gap-2">
                <Button variant="ghost" size="icon" className="lg:hidden" aria-label={t("files.backToTree")} onClick={() => navigate({ search: {} })}>
                  <ArrowLeft className="size-4" />
                </Button>
                <h2 className="min-w-0 truncate font-mono text-sm font-medium" title={file}>
                  {file}
                </h2>
              </div>
              {editing ? <Editor path={file} onClose={() => setEditing(false)} /> : <Viewer key={file} path={file} entry={entry} onEdit={writable ? () => setEditing(true) : undefined} />}
            </div>
          ) : (
            <Empty>{t("files.pick")}</Empty>
          )}
        </section>
      </div>
    </div>
  );
};
