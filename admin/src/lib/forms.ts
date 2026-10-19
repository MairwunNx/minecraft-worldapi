import i18n from "i18next";
import { toast } from "sonner";
import { ApiError } from "@/api/client";

/** WorldAPI's message for its own errors, a generic one for a lost connection. */
export const errorText = (error: unknown) => (error instanceof ApiError ? error.message : i18n.t("errors.network"));

export const showError = (error: unknown) => toast.error(errorText(error));
