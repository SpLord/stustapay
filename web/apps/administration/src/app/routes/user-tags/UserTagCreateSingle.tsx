import * as React from "react";
import { UserTagSecret, useCreateUserTagsMutation, useListUserTagSecretsQuery } from "@/api";
import { UserTagRoutes } from "@/app/routes";
import { CreateLayout } from "@/components";
import { useCurrentNode } from "@/hooks";
import { ProductRestrictionSchema } from "@stustapay/models";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { RestrictionSelect } from "@/components/features";
import { FormikProps } from "formik";
import { Select } from "@stustapay/components";
import { Alert, TextField } from "@mui/material";

const NewSingleUserTagSchema = z.object({
  pin: z.string().min(1),
  secret_id: z.number().int(),
  restriction: ProductRestrictionSchema.nullable(),
});

type NewSingleUserTag = z.infer<typeof NewSingleUserTagSchema>;

const initialValues: NewSingleUserTag = {
  pin: "",
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  secret_id: null as any,
  restriction: null,
};

const SingleTagForm: React.FC<FormikProps<NewSingleUserTag>> = (props) => {
  const { currentNode } = useCurrentNode();
  const { t } = useTranslation();
  const { values, setFieldValue, handleChange, handleBlur } = props;
  const { data: userTagsSecrets, error } = useListUserTagSecretsQuery({ nodeId: currentNode.id });

  if (error) {
    return <Alert severity="error">{`Error loading user tag secrets: ${error}`}</Alert>;
  }

  if (!userTagsSecrets) {
    return null;
  }

  return (
    <>
      <TextField
        label={t("userTag.pin")}
        name="pin"
        value={values.pin}
        onChange={handleChange}
        onBlur={handleBlur}
        variant="outlined"
      />
      <RestrictionSelect
        label={t("userTag.restriction")}
        value={values.restriction}
        onChange={(val) => setFieldValue("restriction", val)}
        multiple={false}
      />
      <Select
        label={t("userTag.secret")}
        multiple={false}
        value={userTagsSecrets.find((v) => v.id === values.secret_id) ?? null}
        options={userTagsSecrets}
        formatOption={(secret: UserTagSecret) => secret.description}
        onChange={(secret) => secret && setFieldValue("secret_id", secret.id)}
      />
    </>
  );
};

export const UserTagCreateSingle: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const [createUserTags] = useCreateUserTagsMutation();

  return (
    <CreateLayout
      title={t("userTag.createSingle")}
      successRoute={UserTagRoutes.list()}
      initialValues={initialValues}
      validationSchema={NewSingleUserTagSchema}
      onSubmit={(tag) =>
        createUserTags({
          nodeId: currentNode.id,
          newUserTags: [
            {
              pin: tag.pin,
              secret_id: tag.secret_id,
              restriction: tag.restriction,
            },
          ],
        })
      }
      form={SingleTagForm}
    />
  );
};
