import { useCurrentNode, useCurrencyFormatter } from "@/hooks";
import { Card, CardContent, Stack, Typography } from "@mui/material";
import { DataGrid, GridColDef } from "@stustapay/framework";
import { Loading } from "@stustapay/components";
import * as React from "react";
import { useTranslation } from "react-i18next";

interface CashierRevenueRow {
  id: string;
  cashier_id: number;
  login: string;
  display_name: string;
  product_id: number;
  product_name: string;
  is_deposit: boolean;
  quantity: number;
  revenue: number;
}

interface CashierSummary {
  cashier_id: number;
  display_name: string;
  total_revenue: number;
  total_tips: number;
  total_deposit: number;
  total_sales: number;
  products: CashierRevenueRow[];
}

export const CashierRevenueReport: React.FC = () => {
  const { t } = useTranslation();
  const { currentNode } = useCurrentNode();
  const formatCurrency = useCurrencyFormatter();
  const [data, setData] = React.useState<CashierRevenueRow[] | null>(null);
  const [loading, setLoading] = React.useState(true);

  React.useEffect(() => {
    fetch(`/api/cashiers/revenue-report?node_id=${currentNode.id}`, {
      headers: { Authorization: `Bearer ${localStorage.getItem("access_token") ?? ""}` },
    })
      .then((r) => r.json())
      .then((rows: any[]) => {
        setData(
          rows.map((r, i) => ({
            ...r,
            id: `${r.cashier_id}-${r.product_id}`,
          }))
        );
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [currentNode.id]);

  if (loading) return <Loading />;
  if (!data || data.length === 0) {
    return (
      <Card>
        <CardContent>
          <Typography>{t("cashierReport.noData", "No sales data yet.")}</Typography>
        </CardContent>
      </Card>
    );
  }

  // Aggregate per cashier
  const cashierMap = new Map<number, CashierSummary>();
  for (const row of data) {
    if (!cashierMap.has(row.cashier_id)) {
      cashierMap.set(row.cashier_id, {
        cashier_id: row.cashier_id,
        display_name: row.display_name,
        total_revenue: 0,
        total_tips: 0,
        total_deposit: 0,
        total_sales: 0,
        products: [],
      });
    }
    const c = cashierMap.get(row.cashier_id)!;
    c.total_revenue += row.revenue;
    c.products.push(row);
    if (row.product_name.toLowerCase().includes("trinkgeld") || row.product_name.toLowerCase().includes("tip")) {
      c.total_tips += row.revenue;
    } else if (row.is_deposit) {
      c.total_deposit += row.revenue;
    } else {
      c.total_sales += row.revenue;
    }
  }

  const summaryRows = Array.from(cashierMap.values()).map((c) => ({
    id: c.cashier_id,
    ...c,
  }));

  const summaryColumns: GridColDef<(typeof summaryRows)[0]>[] = [
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

  const detailColumns: GridColDef<CashierRevenueRow>[] = [
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
            rows={data}
            columns={detailColumns}
            disableRowSelectionOnClick
            sx={{ p: 1, boxShadow: (theme) => theme.shadows[1] }}
          />
        </CardContent>
      </Card>
    </Stack>
  );
};
