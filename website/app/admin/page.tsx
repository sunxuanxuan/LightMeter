import { Database, ReceiptText } from "lucide-react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { connection } from "next/server";

import { PageHeader } from "@/components/page-header";
import { formatPrice } from "@/lib/config";
import { listRecentOrders } from "@/lib/dal";

export const metadata: Metadata = {
  title: "本地运营预览",
};

export default async function AdminPage() {
  if (process.env.NODE_ENV === "production") notFound();
  await connection();
  const orders = listRecentOrders();

  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Local operations"
        title="订单与签发记录"
        description="仅用于本地预览数据核对。生产管理端必须接入独立身份认证和多因素验证。"
        action={
          <span className="badge badge--warning">
            <Database size={13} />
            SQLite 本地库
          </span>
        }
      />

      <div className="notice notice--warning admin-banner">
        <Database size={18} />
        <span>
          此页面在 production 构建中不可访问。真实后台需补充管理员认证、发布管理、换机审核和审计日志。
        </span>
      </div>

      <div className="admin-table-wrap">
        {orders.length ? (
          <table className="admin-table">
            <thead>
              <tr>
                <th>订单号</th>
                <th>平台</th>
                <th>设备尾号</th>
                <th>状态</th>
                <th>金额</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.orderNo}>
                  <td>{order.orderNo}</td>
                  <td>{order.platform}</td>
                  <td>{order.deviceIDSuffix}</td>
                  <td>{order.status}</td>
                  <td>{formatPrice(order.amountMinor, order.currency)}</td>
                  <td>
                    {new Intl.DateTimeFormat("zh-CN", {
                      dateStyle: "short",
                      timeStyle: "short",
                    }).format(new Date(order.createdAt))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="empty-state">
            <ReceiptText size={28} />
            <p>还没有本地订单，请先走一遍激活演示流程。</p>
          </div>
        )}
      </div>
    </div>
  );
}
