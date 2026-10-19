import i18n from "i18next";
import { toast } from "sonner";

/** Copies to the clipboard; browsers allow it on HTTPS and localhost only, so say so otherwise. */
export const copy = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    toast.success(i18n.t("common.copied"));
  } catch {
    toast.error(i18n.t("common.copyFailed"));
  }
};
