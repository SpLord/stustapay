import { Alert, Card, CardContent, Stack, Typography } from "@mui/material";
import { Loading } from "@stustapay/components";
import { DataGrid, GridColDef } from "@stustapay/framework";
import * as React from "react";
import { useTranslation } from "react-i18next";

import { CashierRevenueRow, useGetCashierRevenueReportQuery } from "@/api";
import { useCurrentNode, useCurrencyFormatter } from "@/hooks";

interface CashierSummary {
  id: number;
  display_name: string;
  total_revenue: number;
  total_tips: number;
  total_deposit: number;
  total_sales: number;
}

// Tip products are identified by an exact name match, following the same convention
// as the terminal app (`SaleViewModel.findTipButtonId`). A substring match would
// misclassify products like "Tipsy Cocktail" as tips.
const TIP_PRODUCT_NAMES = new Set(["trinkgeld", "tip"]);
const isTipProduct = (productName: string): boolean => TIP_PRODUCT_NAMES.has(productName.trim().toLowerCase());

export const CashierRevenueReport: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const formatCurrency = useCurrencyFormatter();
  const { data, isLoading, isError } = useGetCashierRevenueReportQuery({ nodeId: currentNode.id });

  if (isLoading) return <Loading />;
  if (isError) return <Alert severity="error">{t("cashierReport.loadError")}</Alert>;
  if (!data || data.length === 0) return null;

  const reportRows: CashierRevenueRow[] = data;

  const rows = reportRows.map((r) => ({
    ...r,
    id: `${r.cashier_id}-${r.product_id}`,
  }));

  // Aggregate per cashier
  const cashierMap = new Map<number, CashierSummary>();
  for (const row of reportRows) {
    if (!cashierMap.has(row.cashier_id)) {
      cashierMap.set(row.cashier_id, {
        id: row.cashier_id,
        display_name: row.display_name,
        total_revenue: 0,
        total_tips: 0,
        total_deposit: 0,
        total_sales: 0,
      });
    }
    const c = cashierMap.get(row.cashier_id)!;
    c.total_revenue += row.revenue;
    if (isTipProduct(row.product_name)) {
      c.total_tips += row.revenue;
    } else if (row.is_deposit) {
      c.total_deposit += row.revenue;
    } else {
      c.total_sales += row.revenue;
    }
  }

  const summaryRows = Array.from(cashierMap.values());

  const summaryColumns: GridColDef<CashierSummary>[] = [
    { field: "display_name", headerName: t("cashierReport.cashier"), flex: 1 },
    {
      field: "total_sales",
      headerName: t("cashierReport.sales"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => formatCurrency(value),
    },
    {
      field: "total_tips",
      headerName: t("cashierReport.tips"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => (value > 0 ? formatCurrency(value) : "—"),
    },
    {
      field: "total_deposit",
      headerName: t("cashierReport.deposit"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => (value !== 0 ? formatCurrency(value) : "—"),
    },
    {
      field: "total_revenue",
      headerName: t("cashierReport.total"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => formatCurrency(value),
    },
  ];

  const detailColumns: GridColDef<(typeof rows)[0]>[] = [
    { field: "display_name", headerName: t("cashierReport.cashier"), flex: 1 },
    { field: "product_name", headerName: t("cashierReport.product"), flex: 1 },
    { field: "quantity", headerName: t("cashierReport.quantity"), type: "number" },
    {
      field: "revenue",
      headerName: t("cashierReport.revenue"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => formatCurrency(value),
    },
  ];

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            {t("cashierReport.summaryTitle")}
          </Typography>
          <DataGrid
            rows={summaryRows}
            columns={summaryColumns}
            disableRowSelectionOnClick
            sx={{ p: 1, boxShadow: (theme) => theme.shadows[1] }}
          />
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            {t("cashierReport.detailTitle")}
          </Typography>
          <DataGrid
            rows={rows}
            columns={detailColumns}
            disableRowSelectionOnClick
            sx={{ p: 1, boxShadow: (theme) => theme.shadows[1] }}
          />
        </CardContent>
      </Card>
    </Stack>
  );
};
