import { CashierRevenueReportRow, useGetCashierRevenueReportQuery } from "@/api";
import { useCurrentNode, useCurrencyFormatter } from "@/hooks";
import { Card, CardContent, Stack, Typography } from "@mui/material";
import { DataGrid, GridColDef } from "@stustapay/framework";
import { Loading } from "@stustapay/components";
import * as React from "react";
import { useTranslation } from "react-i18next";

interface CashierSummary {
  id: number;
  display_name: string;
  total_revenue: number;
  total_tips: number;
  total_deposit: number;
  total_sales: number;
}

export const CashierRevenueReport: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const formatCurrency = useCurrencyFormatter();
  const { data, isLoading } = useGetCashierRevenueReportQuery({ nodeId: currentNode.id });

  if (isLoading) return <Loading />;
  if (!data || data.length === 0) return null;

  const rows = data.map((r, i) => ({
    ...r,
    id: `${r.cashier_id}-${r.product_id}`,
  }));

  // Aggregate per cashier
  const cashierMap = new Map<number, CashierSummary>();
  for (const row of data) {
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
    if (row.product_name.toLowerCase().includes("trinkgeld") || row.product_name.toLowerCase().includes("tip")) {
      c.total_tips += row.revenue;
    } else if (row.is_deposit) {
      c.total_deposit += row.revenue;
    } else {
      c.total_sales += row.revenue;
    }
  }

  const summaryRows = Array.from(cashierMap.values());

  const summaryColumns: GridColDef<CashierSummary>[] = [
    { field: "display_name", headerName: t("cashierReport.cashier", "Cashier"), flex: 1 },
    {
      field: "total_sales",
      headerName: t("cashierReport.sales", "Sales"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => formatCurrency(value),
    },
    {
      field: "total_tips",
      headerName: t("cashierReport.tips", "Tips"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => (value > 0 ? formatCurrency(value) : "—"),
    },
    {
      field: "total_deposit",
      headerName: t("cashierReport.deposit", "Deposit"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => (value !== 0 ? formatCurrency(value) : "—"),
    },
    {
      field: "total_revenue",
      headerName: t("cashierReport.total", "Total"),
      type: "number",
      flex: 1,
      valueFormatter: (value: number) => formatCurrency(value),
    },
  ];

  const detailColumns: GridColDef<(typeof rows)[0]>[] = [
    { field: "display_name", headerName: t("cashierReport.cashier", "Cashier"), flex: 1 },
    { field: "product_name", headerName: t("cashierReport.product", "Product"), flex: 1 },
    { field: "quantity", headerName: t("cashierReport.quantity", "Qty"), type: "number" },
    {
      field: "revenue",
      headerName: t("cashierReport.revenue", "Revenue"),
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
            {t("cashierReport.summaryTitle", "Cashier Summary")}
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
            {t("cashierReport.detailTitle", "Sales by Cashier & Product")}
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
