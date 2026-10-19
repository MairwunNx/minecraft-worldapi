import { useEffect, useId, useState, useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { iconUrl, type Schema } from "@/api/client";
import { Confirm } from "@/components/common";
import { FormField } from "@/components/form-field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { showError } from "@/lib/forms";
import { cn } from "@/lib/utils";

export type Item = Schema<"ItemView">;
export type ItemInput = Schema<"ItemInput">;

/** `minecraft:diamond_sword` reads as “Diamond sword”. */
export const itemName = (type: string) => {
  const words = type.replace(/^minecraft:/, "").replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

/** Two letters for a slot without a picture: the first letters of the last two words, so swords and pickaxes differ. */
const initials = (type: string) => {
  const words = type.replace(/^.*:/, "").split("_").filter(Boolean);
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return words
    .slice(-2)
    .map((word) => word[0])
    .join("")
    .toUpperCase();
};

// ---------- Icons ----------

/**
 * Icons load through one queue instead of <img> error events: a few requests at a time, each
 * picture handed to the page only once it arrived, so a slot never shows the browser's broken
 * image. While WorldAPI still downloads the game's textures (503 icons_loading), or the rate limit
 * says wait (429), a request waits Retry-After and tries again; slots show initials meanwhile.
 * Unknown items (404) keep their initials. Pictures become data: URLs, which the CSP allows.
 */
type IconState = { status: "loading" } | { status: "ok"; url: string } | { status: "missing" };

const icons = new Map<string, IconState>();
const iconListeners = new Set<() => void>();
const queue: string[] = [];
const PARALLEL = 6;
let active = 0;

const setIcon = (key: string, state: IconState) => {
  icons.set(key, state);
  iconListeners.forEach((listener) => listener());
};

const readAsDataUrl = (blob: Blob) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });

const later = (key: string, seconds: number) => window.setTimeout(() => enqueue(key), Math.min(120, Math.max(1, seconds)) * 1000);

const load = async (key: string, attempt: number) => {
  try {
    const response = await fetch(iconUrl(key), { credentials: "same-origin" });
    if (response.ok) return setIcon(key, { status: "ok", url: await readAsDataUrl(await response.blob()) });
    const wait = Number(response.headers.get("Retry-After")) || 0;
    if (response.status === 429 || response.status === 503) {
      // icons_unavailable means the download failed; asking again now and then picks up a later success.
      const code = ((await response.json().catch(() => null)) as { error?: { code?: string } } | null)?.error?.code;
      return later(key, wait || (code === "icons_unavailable" ? 60 : 5));
    }
    setIcon(key, { status: "missing" });
  } catch {
    if (attempt < 5) later(key, 5 * (attempt + 1));
    else setIcon(key, { status: "missing" });
  }
};

const attempts = new Map<string, number>();
const pump = () => {
  while (active < PARALLEL && queue.length) {
    const key = queue.shift()!;
    const attempt = attempts.get(key) ?? 0;
    attempts.set(key, attempt + 1);
    active++;
    void load(key, attempt).finally(() => {
      active--;
      pump();
    });
  }
};

function enqueue(key: string) {
  if (!queue.includes(key)) queue.push(key);
  pump();
}

const useIcon = (key: string) => {
  const state = useSyncExternalStore(
    (listener) => {
      iconListeners.add(listener);
      return () => void iconListeners.delete(listener);
    },
    () => icons.get(key),
  );
  useEffect(() => {
    if (!icons.has(key)) {
      icons.set(key, { status: "loading" });
      enqueue(key);
    }
  }, [key]);
  return state ?? { status: "loading" as const };
};

/**
 * The item's picture, scaled to the box whatever size the server renders it at (16 px flat items,
 * 64 px block cubes), kept sharp; its initials until it arrives, or when the game has none.
 */
export const ItemIcon = ({ type, size, className }: { type: string; size?: number; className?: string }) => {
  const icon = useIcon(type);
  const box = size ? { width: size, height: size } : undefined;
  if (icon.status !== "ok")
    return (
      <span
        className={cn("flex shrink-0 items-center justify-center font-mono font-semibold text-muted-foreground", size && size <= 24 ? "text-[9px]" : "text-[11px]", icon.status === "loading" && "animate-pulse", className)}
        style={box}
        aria-hidden
      >
        {initials(type)}
      </span>
    );
  return <img src={icon.url} alt="" draggable={false} style={box} className={cn("shrink-0 object-contain [image-rendering:pixelated]", className)} />;
};

// ---------- Enchantments, as the game's tooltip shows them ----------

const ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
/** Enchantments with one level: the game shows them without a numeral. */
const SINGLE = new Set(["mending", "silk_touch", "infinity", "flame", "channeling", "multishot", "aqua_affinity", "binding_curse", "vanishing_curse"]);
const CURSE_NAMES: Record<string, string> = { binding_curse: "Curse of Binding", vanishing_curse: "Curse of Vanishing" };

export const isCurse = (key: string) => key.replace(/^.*:/, "").endsWith("_curse");

/** `minecraft:projectile_protection` at 4 reads as “Projectile Protection IV”. */
export const enchantmentText = (key: string, level: number) => {
  const id = key.replace(/^minecraft:/, "");
  const base =
    CURSE_NAMES[id] ??
    id
      .replace(/^.*:/, "")
      .split("_")
      .map((word, index) => (index > 0 && ["of", "the"].includes(word) ? word : word.charAt(0).toUpperCase() + word.slice(1)))
      .join(" ");
  if (SINGLE.has(id) && level === 1) return base;
  return `${base} ${ROMAN[level] ?? level}`;
};

/** One line per enchantment, stored ones of books included, curses last and in red; never cut. */
export const Enchantments = ({ item }: { item: Item }) => {
  const all = Object.entries({ ...(item.enchantments ?? {}), ...(item.storedEnchantments ?? {}) });
  if (!all.length) return null;
  all.sort(([a], [b]) => Number(isCurse(a)) - Number(isCurse(b)));
  return (
    <ul className="flex flex-col text-sm">
      {all.map(([key, level]) => (
        <li key={key} className={isCurse(key) ? "text-destructive" : "text-muted-foreground"}>
          {enchantmentText(key, level)}
        </li>
      ))}
    </ul>
  );
};

/** The game's rarity colors, as text tint only. */
const RARITY: Record<string, string> = { uncommon: "text-chart-4", rare: "text-chart-1", epic: "text-chart-5" };

/** What the game's tooltip shows: name, enchantments, lore, durability, and the item key. */
export const ItemCard = ({ item }: { item: Item }) => {
  const { t } = useTranslation();
  const potion = (item.potion as { type?: string | null } | null | undefined)?.type;
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <ItemIcon type={item.type} size={32} />
        <div className="min-w-0">
          <div className={cn("font-medium break-words", item.rarity && RARITY[item.rarity])}>
            {item.name ?? itemName(item.type)}
            {item.amount > 1 && <span className="tabular text-muted-foreground"> × {item.amount}</span>}
          </div>
          <div className="font-mono text-[11px] break-all text-muted-foreground">{item.type}</div>
        </div>
      </div>
      <Enchantments item={item} />
      {potion && <div className="text-sm text-muted-foreground">{itemName(potion)}</div>}
      {!!item.lore?.length && <p className="text-sm whitespace-pre-line text-chart-5 italic">{item.lore.join("\n")}</p>}
      {item.maxDamage ? <div className="tabular text-xs text-muted-foreground">{t("items.durabilityValue", { left: item.maxDamage - (item.damage ?? 0), max: item.maxDamage })}</div> : null}
      {item.unbreakable && <div className="text-xs text-chart-1">{t("items.unbreakable")}</div>}
      {!!item.contents?.length && (
        <div className="flex flex-col gap-0.5 text-xs text-muted-foreground">
          {item.contents.slice(0, 8).map((inner, index) => (
            <div key={index} className="flex items-center gap-1.5">
              <ItemIcon type={inner.type} size={16} />
              {inner.name ?? itemName(inner.type)} × {inner.amount}
            </div>
          ))}
          {item.contents.length > 8 && <div>{t("items.andMore", { count: item.contents.length - 8 })}</div>}
        </div>
      )}
    </div>
  );
};

// ---------- Editing ----------

/** What a grid may do to its slots. */
export interface SlotActions {
  put: (slot: number, item: ItemInput) => Promise<unknown>;
  clear: (slot: number) => Promise<unknown>;
  /** Takes `amount` out of the slot. */
  take: (slot: number, amount: number, item: Item) => Promise<unknown>;
  /** Why editing is off, such as an offline player; the popover says so. */
  disabled?: string;
}

/**
 * Item keys for the picker: every item a grid showed during this visit. Any other key works too;
 * WorldAPI answers 400 for one the game doesn't know.
 */
const seenKeys = new Set<string>();
const remember = (items: Array<Item | null | undefined>) => {
  for (const item of items) if (item) seenKeys.add(item.type);
};

const fullKey = (key: string) => {
  const clean = key.trim().toLowerCase();
  return clean && !clean.includes(":") ? `minecraft:${clean}` : clean;
};

/** A key with suggestions from the recipes (any other key works too) and an amount. */
const PutForm = ({ initial, onSubmit, label }: { initial?: Item; onSubmit: (input: ItemInput) => Promise<unknown>; label: string }) => {
  const { t } = useTranslation();
  const id = useId();
  const keys = [...seenKeys].sort();
  const [key, setKey] = useState(initial?.type ?? "");
  const [amount, setAmount] = useState(String(initial?.amount ?? 1));
  const [busy, setBusy] = useState(false);
  const target = fullKey(key);
  const needle = key.trim().toLowerCase();
  // The preview asks for the icon once typing pauses, not for every partial key.
  const [preview, setPreview] = useState(target);
  useEffect(() => {
    const timer = window.setTimeout(() => setPreview(target), 400);
    return () => window.clearTimeout(timer);
  }, [target]);
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={async (event) => {
        event.preventDefault();
        if (!target) return;
        setBusy(true);
        try {
          await onSubmit({ type: target, amount: Math.max(1, Number.parseInt(amount, 10) || 1) });
        } catch {
          // The caller showed the error.
        } finally {
          setBusy(false);
        }
      }}
    >
      <FormField id={`${id}-key`} label={t("items.key")} hint={t("items.keyHint")}>
        <Input id={`${id}-key`} list={`${id}-keys`} value={key} onChange={(event) => setKey(event.target.value)} placeholder="minecraft:torch" autoComplete="off" spellCheck={false} className="font-mono text-sm" />
      </FormField>
      <datalist id={`${id}-keys`}>
        {keys
          .filter((option) => !needle || option.includes(needle))
          .slice(0, 200)
          .map((option) => (
            <option key={option} value={option} />
          ))}
      </datalist>
      <div className="flex items-end gap-2">
        <FormField id={`${id}-amount`} label={t("items.amount")}>
          <Input id={`${id}-amount`} type="number" min={1} max={99} inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value)} className="w-24" />
        </FormField>
        {preview && preview === target && <ItemIcon key={preview} type={preview} size={32} className="mb-0.5" />}
        <Button type="submit" size="sm" className="mb-0.5 ml-auto" disabled={busy || !target}>
          {label}
        </Button>
      </div>
    </form>
  );
};

const TakeForm = ({ item, onTake }: { item: Item; onTake: (amount: number) => Promise<unknown> }) => {
  const { t } = useTranslation();
  const id = useId();
  const [amount, setAmount] = useState("1");
  const [busy, setBusy] = useState(false);
  if (item.amount <= 1) return null;
  return (
    <div className="flex items-end gap-2">
      <FormField id={`${id}-take`} label={t("items.takeLabel", { max: item.amount })}>
        <Input id={`${id}-take`} type="number" min={1} max={item.amount} inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value)} className="w-20" />
      </FormField>
      <Button
        size="sm"
        variant="outline"
        className="mb-0.5"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          try {
            await onTake(Math.min(item.amount, Math.max(1, Number.parseInt(amount, 10) || 1)));
          } catch {
            // The caller showed the error.
          } finally {
            setBusy(false);
          }
        }}
      >
        {t("items.take")}
      </Button>
    </div>
  );
};

/** A slot's popover: the item as the game's tooltip shows it, and what the panel may do with it. */
const SlotPanel = ({ slot, item, actions, label, close }: { slot: number; item?: Item | null; actions?: SlotActions; label?: string; close: () => void }) => {
  const { t } = useTranslation();
  const [replacing, setReplacing] = useState(false);
  const act = async (action: () => Promise<unknown>) => {
    try {
      await action();
      close();
    } catch (error) {
      showError(error);
      throw error;
    }
  };
  const name = item ? (item.name ?? itemName(item.type)) : "";
  return (
    <div className="flex flex-col gap-3">
      <div className="text-xs text-muted-foreground">{label ? t("items.slotNamed", { label, slot }) : t("items.slot", { slot })}</div>
      {item ? <ItemCard item={item} /> : <div className="text-sm text-muted-foreground">{t("items.empty")}</div>}
      {actions?.disabled && <p className="text-xs text-muted-foreground">{actions.disabled}</p>}
      {actions && !actions.disabled && (
        <div className="flex flex-col gap-3 border-t pt-3">
          {!item || replacing ? (
            <PutForm initial={item ?? undefined} label={item ? t("items.replace") : t("items.put")} onSubmit={(input) => act(() => actions.put(slot, input))} />
          ) : (
            <>
              <TakeForm item={item} onTake={(amount) => act(() => actions.take(slot, amount, item))} />
              <div className="flex flex-wrap gap-2">
                <Confirm
                  label={t("items.takeAll")}
                  title={t("items.takeAllTitle", { name, count: item.amount })}
                  text={t("items.takeAllText")}
                  action={t("items.takeAll")}
                  onConfirm={() => act(() => actions.take(slot, item.amount, item))}
                />
                <Button size="sm" variant="outline" onClick={() => setReplacing(true)}>
                  {t("items.replace")}
                </Button>
                <Confirm
                  destructive
                  label={t("items.clear")}
                  title={t("items.clearTitle", { slot })}
                  text={t("items.clearText", { name })}
                  action={t("items.clear")}
                  onConfirm={() => act(() => actions.clear(slot))}
                />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};

/** A confirm dialog opened from the popover lives outside it; working in it mustn't close the popover under it. */
const keepForDialogs = (event: { target: EventTarget | null; preventDefault: () => void }) => {
  if (event.target instanceof Element && event.target.closest("[role=alertdialog], [data-slot=alert-dialog-overlay]")) event.preventDefault();
};

const Slot = ({ slot, item, actions, label }: { slot: number; item?: Item | null; actions?: SlotActions; label?: string }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const enchanted = !!item && (Object.keys(item.enchantments ?? {}).length > 0 || Object.keys(item.storedEnchantments ?? {}).length > 0);
  const worn = item?.damage && item.maxDamage ? 1 - item.damage / item.maxDamage : null;
  const button = (
    <button
      type="button"
      aria-label={item ? `${item.name ?? itemName(item.type)}${item.amount > 1 ? ` × ${item.amount}` : ""}` : t("items.emptySlot", { slot: label ?? slot })}
      className={cn(
        "relative flex aspect-square min-w-0 items-center justify-center overflow-hidden rounded-sm border transition-colors hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
        item ? "bg-card" : "border-dashed bg-muted/40 text-[9px] text-muted-foreground",
        enchanted && "border-chart-5/60 bg-chart-5/10",
        open && "ring-2 ring-ring",
      )}
    >
      {item ? <ItemIcon type={item.type} className="h-[82%] w-[82%]" /> : label?.slice(0, 4)}
      {item && item.amount > 1 && <span className="stack-count absolute right-0.5 bottom-0">{item.amount}</span>}
      {worn !== null && (
        <span className="absolute inset-x-0.5 bottom-0.5 h-0.5 rounded-full bg-muted" aria-hidden>
          <span className={cn("block h-full rounded-full", worn > 0.3 ? "bg-success" : "bg-destructive")} style={{ width: `${worn * 100}%` }} />
        </span>
      )}
    </button>
  );
  return (
    <Popover open={open} onOpenChange={setOpen}>
      {/* One tree either way: swapping it would remount the trigger under an open popover. */}
      <Tooltip open={item && !open ? undefined : false}>
        <TooltipTrigger asChild>
          <PopoverTrigger asChild>{button}</PopoverTrigger>
        </TooltipTrigger>
        {item && (
          <TooltipContent side="top" className="max-w-72 border bg-popover px-3 py-2 text-popover-foreground shadow-md [&>span:last-child]:hidden">
            <ItemCard item={item} />
          </TooltipContent>
        )}
      </Tooltip>
      <PopoverContent className="w-80" onFocusOutside={keepForDialogs} onInteractOutside={keepForDialogs}>
        <SlotPanel slot={slot} item={item} actions={actions} label={label} close={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  );
};

/** Slots by number in rows of nine; a click opens the item and what may be done with it. */
export const ItemGrid = ({ items, size, rows: order, actions }: { items: Item[]; size: number; rows?: number[][]; actions?: SlotActions }) => {
  remember(items);
  const bySlot = new Map(items.map((item, index) => [item.slot ?? index, item]));
  const rows = order ?? Array.from({ length: Math.ceil(size / 9) }, (_, row) => Array.from({ length: Math.min(9, size - row * 9) }, (_, column) => row * 9 + column));
  return (
    <div className="flex max-w-md flex-col gap-1">
      {rows.map((row, index) => (
        <div key={index} className={cn("grid grid-cols-9 gap-1", order && index === rows.length - 1 && "mt-2")}>
          {row.map((slot) => (
            <Slot key={slot} slot={slot} item={bySlot.get(slot)} actions={actions} />
          ))}
        </div>
      ))}
    </div>
  );
};

/** The player's inventory as the game draws it: armor and offhand, three rows, then the hotbar. */
export const Inventory = ({ inventory, actions }: { inventory: Schema<"InventoryView">; actions?: SlotActions }) => {
  const { t } = useTranslation();
  // The API's slot numbers: 39 helmet, 38 chestplate, 37 leggings, 36 boots, 40 offhand.
  const gear: Array<[number, string, Item | null | undefined]> = [
    [39, t("items.head"), inventory.armor.head],
    [38, t("items.chest"), inventory.armor.chest],
    [37, t("items.legs"), inventory.armor.legs],
    [36, t("items.feet"), inventory.armor.feet],
    [40, t("items.offhand"), inventory.offhand],
  ];
  remember(gear.map(([, , item]) => item));
  const rows = [0, 1, 2].map((row) => Array.from({ length: 9 }, (_, column) => 9 + row * 9 + column)).concat([Array.from({ length: 9 }, (_, column) => column)]);
  return (
    <div className="flex flex-col gap-4">
      <div className="grid max-w-md grid-cols-9 gap-1">
        {gear.map(([slot, label, item]) => (
          <Slot key={slot} slot={slot} item={item} label={label} actions={actions} />
        ))}
      </div>
      <ItemGrid items={inventory.main} size={36} rows={rows} actions={actions} />
    </div>
  );
};

/** Gives into the first free slots, as /give does; what doesn't fit drops. */
export const GiveButton = ({ onGive }: { onGive: (input: ItemInput) => Promise<unknown> }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button size="sm" variant="outline">
          {t("items.give")}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-80">
        <PutForm
          label={t("items.give")}
          onSubmit={async (input) => {
            try {
              await onGive(input);
              setOpen(false);
            } catch (error) {
              showError(error);
              throw error;
            }
          }}
        />
      </PopoverContent>
    </Popover>
  );
};
