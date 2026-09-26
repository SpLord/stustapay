import { Alert, Button, LinearProgress, Paper, Stack, TextField, Typography } from "@mui/material";
import { Select } from "@stustapay/components";
import * as React from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { toast } from "react-toastify";

import { UserTagSecret, useCreateUserTagsMutation, useListUserTagSecretsQuery } from "@/api";
import { UserTagRoutes } from "@/app/routes";
import { UserTagVariantSelect } from "@/components/features";
import { useCurrentNode } from "@/hooks";

type MutationError = { data?: { detail?: unknown }; error?: unknown };

const formatError = (err: unknown): string => {
  const e = err as MutationError | null | undefined;
  const reason = e?.data?.detail ?? e?.error ?? err;
  return typeof reason === "string" ? reason : String(reason);
};

export const UserTagCreateSingle: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const navigate = useNavigate();
  const [createUserTags, { isLoading }] = useCreateUserTagsMutation();
  const { data: userTagsSecrets, error: secretsError } = useListUserTagSecretsQuery({ nodeId: currentNode.id });

  const [pin, setPin] = React.useState("");
  const [secretId, setSecretId] = React.useState<number | null>(null);
  const [variantId, setVariantId] = React.useState<number | null>(null);

  if (secretsError) {
    return (
      <Alert severity="error">{t("userTag.single.secretsLoadError", { reason: formatError(secretsError) })}</Alert>
    );
  }

  if (!userTagsSecrets) {
    return null;
  }

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedPin = pin.trim();
    if (!trimmedPin) {
      toast.error(t("userTag.single.pinRequired"));
      return;
    }
    if (secretId == null) {
      toast.error(t("userTag.single.secretRequired"));
      return;
    }

    try {
      await createUserTags({
        nodeId: currentNode.id,
        newUserTags: [
          {
            pin: trimmedPin,
            secret_id: secretId,
            variant_ids: variantId != null ? [variantId] : [],
          },
        ],
      }).unwrap();
      toast.success(t("userTag.single.succeeded", { pin: trimmedPin }));
      navigate(UserTagRoutes.list());
    } catch (err) {
      toast.error(t("userTag.single.failed", { reason: formatError(err) }));
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <Stack spacing={2}>
        <Typography component="div" variant="h5">
          {t("userTag.createSingle")}
        </Typography>
        <Paper sx={{ p: 3 }}>
          <Stack spacing={2}>
            <TextField
              label={t("userTag.singlePinLabel")}
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              variant="outlined"
              fullWidth
            />
            <Select
              label={t("userTag.singleSecretLabel")}
              multiple={false}
              value={userTagsSecrets.find((v) => v.id === secretId) ?? null}
              options={userTagsSecrets}
              formatOption={(secret: UserTagSecret) => secret.description}
              onChange={(secret) => secret && setSecretId(secret.id)}
            />
            <UserTagVariantSelect
              label={t("userTag.variants")}
              value={variantId}
              onChange={(val) => setVariantId(val)}
              multiple={false}
            />
            {isLoading && <LinearProgress />}
          </Stack>
        </Paper>
        <Button
          type="submit"
          variant="contained"
          color="primary"
          disabled={isLoading || !pin.trim() || secretId == null}
          fullWidth
        >
          {t("userTag.createSingleButton")}
        </Button>
      </Stack>
    </form>
  );
};
