import { useQuery } from "@tanstack/react-query";
import { ShieldAlert } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { statusQuery } from "@/lib/session";

/** Shown when WorldAPI saw this browser come from the internet without HTTPS: passwords and tokens travel in the open. */
export const InsecureBanner = ({ className }: { className?: string }) => {
  const { t } = useTranslation();
  const status = useQuery(statusQuery).data;
  if (!status || status.secure !== false) return null;
  return (
    <Alert variant="destructive" className={className}>
      <ShieldAlert />
      <AlertTitle>{t("insecure.title")}</AlertTitle>
      <AlertDescription>
        <p>
          <Trans i18nKey="insecure.body" components={{ code: <code className="font-mono" /> }} />
        </p>
      </AlertDescription>
    </Alert>
  );
};
