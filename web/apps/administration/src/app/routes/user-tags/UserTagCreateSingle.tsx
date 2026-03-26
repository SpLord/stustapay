import * as React from "react";
import { UserTagSecret, useCreateUserTagsMutation, useListUserTagSecretsQuery } from "@/api";
import { UserTagRoutes } from "@/app/routes";
import { useCurrentNode } from "@/hooks";
import { useTranslation } from "react-i18next";
import { RestrictionSelect } from "@/components/features";
import { Select } from "@stustapay/components";
import { Alert, Button, LinearProgress, Paper, Stack, TextField, Typography } from "@mui/material";
import { useNavigate } from "react-router-dom";
import { toast } from "react-toastify";

export const UserTagCreateSingle: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const navigate = useNavigate();
  const [createUserTags, { isLoading }] = useCreateUserTagsMutation();
  const { data: userTagsSecrets, error: secretsError } = useListUserTagSecretsQuery({ nodeId: currentNode.id });

  const [pin, setPin] = React.useState("");
  const [secretId, setSecretId] = React.useState<number | null>(null);
  const [restriction, setRestriction] = React.useState<string | null>(null);

  if (secretsError) {
    return <Alert severity="error">{`Error loading user tag secrets: ${secretsError}`}</Alert>;
  }

  if (!userTagsSecrets) {
    return null;
  }

  const handleSubmit = async () => {
    if (!pin.trim()) {
      toast.error("PIN is required");
      return;
    }
    if (secretId == null) {
      toast.error("Secret is required");
      return;
    }

    try {
      await createUserTags({
        nodeId: currentNode.id,
        newUserTags: [
          {
            pin: pin.trim(),
            secret_id: secretId,
            restriction: restriction,
          },
        ],
      }).unwrap();
      toast.success(`Tag "${pin.trim()}" created`);
      navigate(UserTagRoutes.list());
    } catch (err) {
      toast.error(`Error creating tag: ${err}`);
    }
  };

  return (
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
          <RestrictionSelect
            label={t("userTag.singleRestrictionLabel")}
            value={restriction}
            onChange={(val) => setRestriction(val)}
            multiple={false}
          />
          {isLoading && <LinearProgress />}
        </Stack>
      </Paper>
      <Button
        variant="contained"
        color="primary"
        onClick={handleSubmit}
        disabled={isLoading || !pin.trim() || secretId == null}
        fullWidth
      >
        {t("userTag.createSingleButton")}
      </Button>
    </Stack>
  );
};
