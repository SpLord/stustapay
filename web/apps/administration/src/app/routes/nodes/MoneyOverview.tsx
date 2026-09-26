import { Alert, Box, Card, CardActions, CardContent, Grid, Stack, Typography, useTheme } from "@mui/material";
import { Loading } from "@stustapay/components";
import * as React from "react";
import { useTranslation } from "react-i18next";

import { Account, AccountRead, AccountType, useGetMoneyOverviewQuery } from "@/api";
import { SystemAccountRoutes } from "@/app/routes";
import { ButtonLink } from "@/components";
import { useCurrencyFormatter, useCurrentNode } from "@/hooks";

interface BalanceCardProps {
  amount: number;
  label?: string | null;
  actions?: React.ReactNode;
}

const BalanceCard: React.FC<BalanceCardProps> = ({ amount, label, actions }) => {
  const formatCurrency = useCurrencyFormatter();

  return (
    <Card>
      <CardContent>
        <Grid
          container
          sx={{
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            flexDirection: "column",
          }}
        >
          <Grid>
            <Typography variant="h6" component="div">
              {label}
            </Typography>
          </Grid>
          <Grid>
            <Typography component="span" variant="body1">
              {formatCurrency(amount)}
            </Typography>
          </Grid>
        </Grid>
      </CardContent>
      {actions && <CardActions>{actions}</CardActions>}
    </Card>
  );
};

interface AccountBalanceCardProps {
  account?: Account;
}

const AccountBalanceCard: React.FC<AccountBalanceCardProps> = ({ account }) => {
  const { t } = useTranslation();

  if (!account) {
    return null;
  }

  return (
    <BalanceCard
      amount={account.balance}
      label={account.name}
      actions={
        <ButtonLink size="small" to={SystemAccountRoutes.detail(account.id)}>
          {t("overview.showDetails")}
        </ButtonLink>
      }
    />
  );
};

export const MoneyOverview: React.FC = () => {
  const theme = useTheme();
  const { t } = useTranslation();
  const formatCurrency = useCurrencyFormatter();
  const { currentNode } = useCurrentNode();
  const {
    data: moneyOverviewData,
    isLoading: isAccountsLoading,
    isError,
  } = useGetMoneyOverviewQuery({
    nodeId: currentNode.id,
  });

  if (isError) {
    return <Alert severity="error">{t("overview.statsLoadError")}</Alert>;
  }

  if (!moneyOverviewData || isAccountsLoading) {
    return <Loading />;
  }

  const selectAccountByType = (type: AccountType): AccountRead | undefined => {
    return moneyOverviewData?.system_accounts.find((a) => a.type === type);
  };

  const deposit = moneyOverviewData.deposit_overview;

  return (
    <Stack spacing={2}>
      {deposit && (deposit.total_deposit_charged > 0 || deposit.total_deposit_returned > 0) && (
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              {t("overview.deposit")}
            </Typography>
            <Grid container spacing={2}>
              <Grid size={4}>
                <Typography variant="body2" color="text.secondary">
                  {t("overview.depositCharged")}
                </Typography>
                <Typography variant="h5">{formatCurrency(deposit.total_deposit_charged)}</Typography>
              </Grid>
              <Grid size={4}>
                <Typography variant="body2" color="text.secondary">
                  {t("overview.depositReturned")}
                </Typography>
                <Typography variant="h5">{formatCurrency(deposit.total_deposit_returned)}</Typography>
              </Grid>
              <Grid size={4}>
                <Typography variant="body2" color="text.secondary">
                  {t("overview.depositOutstanding")}
                </Typography>
                <Typography variant="h5" color={deposit.deposit_balance < 0 ? "error.main" : "success.main"}>
                  {formatCurrency(deposit.deposit_balance)}
                </Typography>
              </Grid>
            </Grid>
          </CardContent>
        </Card>
      )}
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "repeat(2, 1fr)", md: "repeat(6, 1fr)" },
          gap: theme.spacing(1),
        }}
      >
        <AccountBalanceCard account={selectAccountByType("cash_vault")} />
        <AccountBalanceCard account={selectAccountByType("sumup_entry")} />
        <AccountBalanceCard account={selectAccountByType("sumup_online_entry")} />
        <AccountBalanceCard account={selectAccountByType("sale_exit")} />
        <AccountBalanceCard account={selectAccountByType("cash_imbalance")} />
        <AccountBalanceCard account={selectAccountByType("cash_entry")} />
        <AccountBalanceCard account={selectAccountByType("cash_exit")} />
        <AccountBalanceCard account={selectAccountByType("sepa_exit")} />
        <AccountBalanceCard account={selectAccountByType("donation_exit")} />
        <BalanceCard label="Customer balance" amount={moneyOverviewData.total_customer_account_balance} />
        <BalanceCard label="Cash registers" amount={moneyOverviewData.total_cash_register_balance} />
      </Box>
    </Stack>
  );
};
