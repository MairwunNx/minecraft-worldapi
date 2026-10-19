import { useTranslation } from "react-i18next";

/** A block on a grid: the world WorldAPI reads, in the green of its icon. */
export const Logo = () => {
  const { t } = useTranslation();
  return (
    <span className="flex items-center gap-2 font-semibold tracking-tight">
      <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
        <rect width="32" height="32" rx="8" className="fill-primary" />
        <path d="M8 11h16M8 16h16M8 21h16M16 8v16" className="stroke-primary-foreground" strokeWidth="2" strokeLinecap="round" opacity=".45" />
        <rect x="9" y="9" width="14" height="14" rx="2" fill="none" className="stroke-primary-foreground" strokeWidth="2.4" />
      </svg>
      {t("app.name")}
    </span>
  );
};
