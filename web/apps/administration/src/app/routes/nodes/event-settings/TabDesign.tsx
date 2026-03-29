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
import { Button, Card, Grid, Stack, Typography } from "@mui/material";
import * as React from "react";
import { useTranslation } from "react-i18next";
import { toast } from "react-toastify";

const toBase64 = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
};

const ACCEPTED_IMAGE_TYPES = ["image/png", "image/jpeg", "image/svg+xml"];

interface LogoUploadSectionProps {
  title: string;
  hint: string;
  description?: string;
  blobId?: string | null;
  accept: string;
  allowedTypes: string[];
  inputId: string;
  uploadFn: (blob: { data: string; mime_type: string }) => Promise<unknown>;
  buttonLabel: string;
}

const LogoUploadSection: React.FC<LogoUploadSectionProps> = ({
  title,
  hint,
  description,
  blobId,
  accept,
  allowedTypes,
  inputId,
  uploadFn,
  buttonLabel,
}) => {
  const selectFile: React.ChangeEventHandler<HTMLInputElement> = async (event) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    if (!allowedTypes.includes(file.type)) {
      toast.error(`Erlaubte Formate: ${allowedTypes.join(", ")}`);
      return;
    }
    try {
      const imageAsBase64 = await toBase64(file);
      await uploadFn({
        data: imageAsBase64.split(",")[1],
        mime_type: file.type,
      });
    } catch (e) {
      toast.error("Error uploading logo");
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
          <img
            style={{ maxWidth: "100%", maxHeight: 200, objectFit: "contain" }}
            src={getBlobUrl(blobId)}
            alt=""
          />
        </Grid>
      )}
      <label htmlFor={inputId}>
        <input
          id={inputId}
          name={inputId}
          style={{ display: "none" }}
          type="file"
          accept={accept}
          onChange={selectFile}
        />
        <Button component="span">{buttonLabel}</Button>
      </label>
    </Card>
  );
};

export const TabDesign: React.FC<{ nodeId: number; eventSettings: RestrictedEventSettings }> = ({
  nodeId,
  eventSettings,
}) => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const { data: eventDesign } = useGetEventDesignQuery({ nodeId: currentNode.id });
  const [updateBonLogo] = useUpdateBonLogoMutation();
  const [updateAppLogo] = useUpdateAppLogoMutation();
  const [updateCustomerLogo] = useUpdateCustomerLogoMutation();
  const [updateWristbandGuide] = useUpdateWristbandGuideMutation();

  const selectBonLogoFile: React.ChangeEventHandler<HTMLInputElement> = async (event) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    if (file.type !== "image/svg+xml") {
      toast.error("Only SVG images are allowed");
      return;
    }
    try {
      const imageAsBase64 = await toBase64(file);
      await updateBonLogo({
        nodeId: currentNode.id,
        newBlob: { data: imageAsBase64.split(",")[1], mime_type: file.type }, // TODO: remove the ugly hack
      });
    } catch (e) {
      toast.error("Error uploading logo");
    }
  };

  return (
    <Stack spacing={2}>
      <LogoUploadSection
        title="App-Logo (Terminal-App)"
        hint="Empfohlen: 512 x 512 px, PNG/JPG"
        blobId={eventDesign?.app_logo_blob_id}
        accept="image/png,image/jpeg,image/svg+xml"
        allowedTypes={ACCEPTED_IMAGE_TYPES}
        inputId="btn-upload-app-logo"
        uploadFn={(blob) => updateAppLogo({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeBonLogo")}
      />

      <LogoUploadSection
        title="Customer Portal Logo"
        hint="Empfohlen: 300 x 100 px, PNG/JPG"
        blobId={eventDesign?.customer_logo_blob_id}
        accept="image/png,image/jpeg,image/svg+xml"
        allowedTypes={ACCEPTED_IMAGE_TYPES}
        inputId="btn-upload-customer-logo"
        uploadFn={(blob) => updateCustomerLogo({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeBonLogo")}
      />

      <LogoUploadSection
        title="Wristband Guide (Band-Anleitung)"
        hint="Empfohlen: 600 x 400 px, PNG/JPG"
        description="Zeigt G\u00e4sten wo die PIN auf dem Band steht"
        blobId={eventDesign?.wristband_guide_blob_id}
        accept="image/png,image/jpeg,image/svg+xml"
        allowedTypes={ACCEPTED_IMAGE_TYPES}
        inputId="btn-upload-wristband-guide"
        uploadFn={(blob) => updateWristbandGuide({ nodeId: currentNode.id, newBlob: blob }).unwrap()}
        buttonLabel={t("settings.design.changeBonLogo")}
      />

      <Card sx={{ p: 2 }}>
        <Typography>{t("settings.design.bonLogo")}</Typography>
        <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
          Nur SVG, schwarz-wei\u00df
        </Typography>
        {eventDesign?.bon_logo_blob_id && (
          <Grid>
            <img width="100%" src={getBlobUrl(eventDesign?.bon_logo_blob_id)} alt="" />
          </Grid>
        )}
        <label htmlFor="btn-upload">
          <input
            id="btn-upload"
            name="btn-upload"
            style={{ display: "none" }}
            type="file"
            accept="image/svg+xml"
            onChange={selectBonLogoFile}
          />
          <Button component="span">{t("settings.design.changeBonLogo")}</Button>
        </label>
      </Card>
    </Stack>
  );
};
