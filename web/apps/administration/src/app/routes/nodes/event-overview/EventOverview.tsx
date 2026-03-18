import { useListExternalTicketsQuery } from "@/api";
import { useCurrentNode, useCurrencyFormatter } from "@/hooks";
import { Card, CardContent, Grid, Stack, Typography } from "@mui/material";
import * as React from "react";
import { useTranslation } from "react-i18next";
import { MoneyOverview } from "../MoneyOverview";

const PresaleStatsCard: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const formatCurrency = useCurrencyFormatter();
  const { data: tickets } = useListExternalTicketsQuery({ nodeId: currentNode.id });

  if (!tickets || tickets.length === 0) {
    return null;
  }

  const activeTickets = tickets.filter((t) => !t.cancelled);
  const cancelledTickets = tickets.filter((t) => t.cancelled);
  const checkedIn = activeTickets.filter((t) => t.has_checked_in);
  const totalTopUp = activeTickets.reduce((sum, t) => sum + (t.initial_top_up_amount ?? 0), 0);
  const checkedInTopUp = checkedIn.reduce((sum, t) => sum + (t.initial_top_up_amount ?? 0), 0);
  const pendingTopUp = totalTopUp - checkedInTopUp;

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" gutterBottom>
          {t("overview.presaleStats", "Presale Tickets")}
        </Typography>
        <Grid container spacing={2}>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presaleTotal", "Total")}
            </Typography>
            <Typography variant="h5">{activeTickets.length}</Typography>
          </Grid>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presaleCheckedIn", "Checked In")}
            </Typography>
            <Typography variant="h5">{checkedIn.length}</Typography>
          </Grid>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presaleCancelled", "Cancelled")}
            </Typography>
            <Typography variant="h5">{cancelledTickets.length}</Typography>
          </Grid>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presaleTotalTopUp", "Total Credit Sold")}
            </Typography>
            <Typography variant="h5">{formatCurrency(totalTopUp)}</Typography>
          </Grid>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presaleActiveTopUp", "Credit Activated")}
            </Typography>
            <Typography variant="h5">{formatCurrency(checkedInTopUp)}</Typography>
          </Grid>
          <Grid size={4}>
            <Typography variant="body2" color="text.secondary">
              {t("overview.presalePendingTopUp", "Credit Pending")}
            </Typography>
            <Typography variant="h5">{formatCurrency(pendingTopUp)}</Typography>
          </Grid>
        </Grid>
      </CardContent>
    </Card>
  );
};

export const EventOverview: React.FC = () => {
  return (
    <Stack spacing={2}>
      <PresaleStatsCard />
      <MoneyOverview />
    </Stack>
  );
};
