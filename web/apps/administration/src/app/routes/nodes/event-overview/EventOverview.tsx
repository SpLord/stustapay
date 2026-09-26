import { Stack } from "@mui/material";
import * as React from "react";

import { CashierRevenueReport } from "../CashierRevenueReport";
import { MoneyOverview } from "../MoneyOverview";
import { PresaleStatsCard } from "../stats/PresaleStatsCard";

export const EventOverview: React.FC = () => {
  return (
    <Stack spacing={2}>
      <PresaleStatsCard />
      <MoneyOverview />
      <CashierRevenueReport />
    </Stack>
  );
};
