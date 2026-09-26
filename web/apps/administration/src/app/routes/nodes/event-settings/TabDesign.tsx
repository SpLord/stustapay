import { Button, Card, Grid, Stack, Typography } from "@mui/material";
import * as React from "react";
import { useTranslation } from "react-i18next";
import { toast } from "react-toastify";

import {
  RestrictedEventSettings,
  useGetEventDesignQuery,
  useUpdateAppLogoMutation,
  useUpdateBonLogoMutation,
  useUpdateCustomerLogoMutation,
  useUpdateWristbandGuideMutation,
} from "@/api";
import { getBlobUrl } from "@/core/blobs";
import { useCurrentNode } from "@/hooks";

const toBase64 = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener("load", () => resolve(reader.result as string));
    reader.addEventListener("error", reject);
    reader.readAsDataURL(file);
  });
};

const RASTER_IMAGE_TYPES = ["image/png", "image/jpeg"];
const RASTER_OR_SVG_IMAGE_TYPES = [...RASTER_IMAGE_TYPES, "image/svg+xml"];
const SVG_IMAGE_TYPES = ["image/svg+xml"];

type UploadError = { data?: { detail?: unknown }; error?: unknown };

const formatUploadError = (err: unknown): string => {
  const e = err as UploadError | null | undefined;
  const reason = e?.data?.detail ?? e?.error ?? err;
  return typeof reason === "string" ? reason : String(reason);
};

interface LogoUploadSectionProps {
  title: string;
  hint: string;
  description?: string;
  blobId?: string | null;
  allowedTypes: string[];
  inputId: string;
  isUploading: boolean;
  uploadFn: (blob: { data: string; mime_type: string }) => Promise<unknown>;
  buttonLabel: string;
}

const LogoUploadSection: React.FC<LogoUploadSectionProps> = ({
  title,
  hint,
  description,
  blobId,
  allowedTypes,
  inputId,
  isUploading,
  uploadFn,
  buttonLabel,
}) => {
  const { t } = useTranslation();

  const selectFile: React.ChangeEventHandler<HTMLInputElement> = async (event) => {
    const file = event.target.files?.[0];
    try {
      if (!file) {
        return;
      }
      if (!allowedTypes.includes(file.type)) {
        toast.error(t("settings.design.allowedFormats", { formats: allowedTypes.join(", ") }));
        return;
      }
      const imageAsBase64 = await toBase64(file);
      await uploadFn({
        data: imageAsBase64.split(",")[1],
        mime_type: file.type,
      });
      toast.success(t("settings.design.uploadSucceeded"));
    } catch (err) {
      toast.error(t("settings.design.uploadFailed", { reason: formatUploadError(err) }));
    } finally {
      // reset the input so selecting the same file again triggers onChange
      event.target.value = "";
    }
  };

  return (
    <Card sx={{ p: 2 }}>
      <Typography variant="h6">{title}</Typography>
      <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
        {hint}
      </Typography>
      {description && (
        <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
          {description}
        </Typography>
      )}
      {blobId && (
        <Grid sx={{ mb: 1 }}>
          <img style={{ maxWidth: "100%", maxHeight: 200, objectFit: "contain" }} src={getBlobUrl(blobId)} alt="" />
        </Grid>
      )}
      <label htmlFor={inputId}>
        <input
          id={inputId}
          name={inputId}
          style={{ display: "none" }}
          type="file"
          accept={allowedTypes.join(",")}
          disabled={isUploading}
          onChange={selectFile}
        />
        <Button component="span" disabled={isUploading}>
          {buttonLabel}
        </Button>
      </label>
    </Card>
  );
};

export const TabDesign: React.FC<{ nodeId: number; eventSettings: RestrictedEventSettings }> = ({
  nodeId: _nodeId,
  eventSettings: _eventSettings,
}) => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const { data: eventDesign } = useGetEventDesignQuery({ nodeId: currentNode.id });
  const [updateBonLogo, { isLoading: isBonLogoUploading }] = useUpdateBonLogoMutation();
  const [updateAppLogo, { isLoading: isAppLogoUploading }] = useUpdateAppLogoMutation();
  const [updateCustomerLogo, { isLoading: isCustomerLogoUploading }] = useUpdateCustomerLogoMutation();
  const [updateWristbandGuide, { isLoading: isWristbandGuideUploading }] = useUpdateWristbandGuideMutation();

  return (
    <Stack spacing={2}>
      <LogoUploadSection
        title={t("settings.design.appLogo")}
        hint={t("settings.design.appLogoHint")}
        blobId={eventDesign?.app_logo_blob_id}
        allowedTypes={RASTER_IMAGE_TYPES}
        inputId="btn-upload-app-logo"
        isUploading={isAppLogoUploading}
        uploadFn={(blob) => updateAppLogo({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeLogo")}
      />

      <LogoUploadSection
        title={t("settings.design.customerLogo")}
        hint={t("settings.design.customerLogoHint")}
        blobId={eventDesign?.customer_logo_blob_id}
        allowedTypes={RASTER_OR_SVG_IMAGE_TYPES}
        inputId="btn-upload-customer-logo"
        isUploading={isCustomerLogoUploading}
        uploadFn={(blob) => updateCustomerLogo({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeLogo")}
      />

      <LogoUploadSection
        title={t("settings.design.wristbandGuide")}
        hint={t("settings.design.wristbandGuideHint")}
        description={t("settings.design.wristbandGuideDescription")}
        blobId={eventDesign?.wristband_guide_blob_id}
        allowedTypes={RASTER_OR_SVG_IMAGE_TYPES}
        inputId="btn-upload-wristband-guide"
        isUploading={isWristbandGuideUploading}
        uploadFn={(blob) => updateWristbandGuide({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeLogo")}
      />

      <LogoUploadSection
        title={t("settings.design.bonLogo")}
        hint={t("settings.design.bonLogoHint")}
        blobId={eventDesign?.bon_logo_blob_id}
        allowedTypes={SVG_IMAGE_TYPES}
        inputId="btn-upload-bon-logo"
        isUploading={isBonLogoUploading}
        uploadFn={(blob) => updateBonLogo({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeBonLogo")}
      />
    </Stack>
  );
};
