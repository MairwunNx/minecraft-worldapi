import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { Schema } from "@/api/client";
import { Definition } from "@/components/common";
import { cn } from "@/lib/utils";

type Item = Schema<"ItemView">;

/** `minecraft:diamond_sword` reads as “Diamond sword”. */
export const itemName = (type: string) => {
  const words = type.replace(/^minecraft:/, "").replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

/** Two letters for a slot: the first letters of the last two words, so swords and pickaxes differ. */
const initials = (type: string) => {
  const words = type.replace(/^.*:/, "").split("_").filter(Boolean);
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return words
    .slice(-2)
    .map((word) => word[0])
    .join("")
    .toUpperCase();
};

/** The game's rarity colors, as text tint only. */
const RARITY: Record<string, string> = { uncommon: "text-chart-4", rare: "text-chart-1", epic: "text-chart-5" };

const Slot = ({ item, selected, onSelect, label }: { item?: Item | null; selected: boolean; onSelect: () => void; label?: string }) => {
  const { t } = useTranslation();
  if (!item)
    return (
      <div className="flex aspect-square items-center justify-center rounded-sm border border-dashed bg-muted/40 text-[9px] text-muted-foreground" aria-label={label ?? t("items.empty")}>
        {label}
      </div>
    );
  const enchanted = Object.keys(item.enchantments ?? {}).length > 0 || Object.keys(item.storedEnchantments ?? {}).length > 0;
  const worn = item.damage && item.maxDamage ? 1 - item.damage / item.maxDamage : null;
  return (
    <button
      type="button"
      onClick={onSelect}
      title={item.name ?? itemName(item.type)}
      aria-pressed={selected}
      className={cn(
        "relative flex aspect-square min-w-0 flex-col items-center justify-center overflow-hidden rounded-sm border bg-card font-mono text-[11px] font-semibold transition-colors hover:bg-accent",
        enchanted && "border-chart-5/60 bg-chart-5/10",
        selected && "ring-2 ring-ring",
        item.rarity && RARITY[item.rarity],
      )}
    >
      {initials(item.type)}
      {item.amount > 1 && <span className="tabular absolute right-0.5 bottom-0 text-[10px] text-foreground">{item.amount}</span>}
      {worn !== null && (
        <span className="absolute inset-x-0.5 bottom-0.5 h-0.5 rounded-full bg-muted" aria-hidden>
          <span className={cn("block h-full rounded-full", worn > 0.3 ? "bg-success" : "bg-destructive")} style={{ width: `${worn * 100}%` }} />
        </span>
      )}
    </button>
  );
};

const Details = ({ item }: { item: Item }) => {
  const { t } = useTranslation();
  const enchantments = { ...(item.enchantments ?? {}), ...(item.storedEnchantments ?? {}) };
  const rows: Array<[string, React.ReactNode]> = [
    [t("items.type"), <span className="font-mono text-xs">{item.type}</span>],
    [t("items.amount"), item.amount],
  ];
  if (item.maxDamage) rows.push([t("items.durability"), `${item.maxDamage - (item.damage ?? 0)} / ${item.maxDamage}`]);
  if (Object.keys(enchantments).length)
    rows.push([
      t("items.enchantments"),
      Object.entries(enchantments)
        .map(([key, level]) => `${itemName(key)} ${level}`)
        .join(", "),
    ]);
  if (item.potion) rows.push([t("items.potion"), item.potion.type ?? "—"]);
  if (item.contents?.length) rows.push([t("items.contents"), t("items.stacks", { count: item.contents.length })]);
  return (
    <div className="mt-3 rounded-md border bg-muted/30 p-3">
      <div className="mb-2 text-sm font-medium">{item.name ?? itemName(item.type)}</div>
      <Definition items={rows} />
      {!!item.lore?.length && <p className="mt-2 text-xs whitespace-pre-line text-muted-foreground">{item.lore.join("\n")}</p>}
    </div>
  );
};

/** Slots by number in rows of nine; a click shows the item's details under the grid. */
export const ItemGrid = ({ items, size, rows: order }: { items: Item[]; size: number; rows?: number[][] }) => {
  const [selected, setSelected] = useState<number | null>(null);
  const bySlot = new Map(items.map((item, index) => [item.slot ?? index, item]));
  const rows = order ?? Array.from({ length: Math.ceil(size / 9) }, (_, row) => Array.from({ length: 9 }, (_, column) => row * 9 + column));
  const current = selected === null ? undefined : bySlot.get(selected);
  return (
    <div className="max-w-md">
      <div className="flex flex-col gap-1">
        {rows.map((row, index) => (
          <div key={index} className={cn("grid grid-cols-9 gap-1", order && index === rows.length - 1 && "mt-2")}>
            {row.map((slot) => (
              <Slot key={slot} item={bySlot.get(slot)} selected={selected === slot} onSelect={() => setSelected(selected === slot ? null : slot)} />
            ))}
          </div>
        ))}
      </div>
      {current && <Details item={current} />}
    </div>
  );
};

/** The player's inventory as the game draws it: three rows, then the hotbar; armor and offhand beside. */
export const Inventory = ({ inventory }: { inventory: Schema<"InventoryView"> }) => {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<string | null>(null);
  const gear: Array<[string, Item | null | undefined]> = [
    [t("items.head"), inventory.armor.head],
    [t("items.chest"), inventory.armor.chest],
    [t("items.legs"), inventory.armor.legs],
    [t("items.feet"), inventory.armor.feet],
    [t("items.offhand"), inventory.offhand],
  ];
  const current = gear.find(([label]) => label === selected)?.[1];
  const rows = [0, 1, 2].map((row) => Array.from({ length: 9 }, (_, column) => 9 + row * 9 + column)).concat([Array.from({ length: 9 }, (_, column) => column)]);
  return (
    <div className="flex flex-col gap-4">
      <div className="max-w-md">
        <div className="grid grid-cols-9 gap-1">
          {gear.map(([label, item]) => (
            <Slot key={label} item={item} label={label.slice(0, 4)} selected={selected === label} onSelect={() => setSelected(selected === label ? null : label)} />
          ))}
        </div>
        {current && <Details item={current} />}
      </div>
      <ItemGrid items={inventory.main} size={36} rows={rows} />
    </div>
  );
};
