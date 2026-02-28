"use client";

import { useState } from "react";
import type { OrderDto } from "@/api/client";
import { findById, findByStatus } from "@/api/client";

export default function Home() {
  const [order, setOrder] = useState<OrderDto | null>(null);
  const [orders, setOrders] = useState<OrderDto[]>([]);
  const [orderId, setOrderId] = useState("1");
  const [status, setStatus] = useState("PENDING");
  const [error, setError] = useState<string | null>(null);

  async function handleFindById() {
    setError(null);
    try {
      const result = await findById(Number(orderId));
      setOrder(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unknown error");
    }
  }

  async function handleFindByStatus() {
    setError(null);
    try {
      const result = await findByStatus(status);
      setOrders(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unknown error");
    }
  }

  return (
    <main>
      {error && (
        <div style={{ padding: "0.75rem", background: "#fee", border: "1px solid #fcc", borderRadius: 4, marginBottom: "1rem" }}>
          {error}
        </div>
      )}

      <section style={{ marginBottom: "2rem" }}>
        <h2>Find Order by ID</h2>
        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
          <input
            type="number"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            style={{ padding: "0.5rem", border: "1px solid #ccc", borderRadius: 4 }}
          />
          <button onClick={handleFindById} style={buttonStyle}>
            Find
          </button>
        </div>
        {order && <OrderCard order={order} />}
      </section>

      <section>
        <h2>Find Orders by Status</h2>
        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            style={{ padding: "0.5rem", border: "1px solid #ccc", borderRadius: 4 }}
          >
            <option value="PENDING">PENDING</option>
            <option value="SHIPPED">SHIPPED</option>
            <option value="DELIVERED">DELIVERED</option>
            <option value="CANCELLED">CANCELLED</option>
          </select>
          <button onClick={handleFindByStatus} style={buttonStyle}>
            Search
          </button>
        </div>
        {orders.length > 0 ? (
          <div style={{ display: "flex", flexWrap: "wrap", gap: "1rem", marginTop: "1rem" }}>
            {orders.map((o) => (
              <OrderCard key={o.id} order={o} />
            ))}
          </div>
        ) : (
          <p style={{ color: "#999" }}>No orders found</p>
        )}
      </section>

      <section style={{ marginTop: "3rem", padding: "1rem", background: "#e8f5e9", borderRadius: 4 }}>
        <h3 style={{ margin: "0 0 0.5rem" }}>PII Safety Verification</h3>
        <p style={{ margin: 0, color: "#2e7d32" }}>
          The fields shown above (<code>id</code>, <code>status</code>, <code>totalAmountCents</code>, <code>itemCount</code>)
          are the only fields exposed by the generated <code>OrderDto</code>.
          Sensitive fields like <code>creditCardNumber</code> and <code>customerSsn</code> are
          structurally excluded at compile time and never reach this frontend.
        </p>
      </section>
    </main>
  );
}

function OrderCard({ order }: { order: OrderDto }) {
  return (
    <div style={{
      padding: "1rem",
      background: "white",
      border: "1px solid #ddd",
      borderRadius: 8,
      marginTop: "0.75rem",
      minWidth: 240,
    }}>
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <tbody>
          <Row label="ID" value={String(order.id)} />
          <Row label="Status" value={order.status} />
          <Row label="Total (cents)" value={order.totalAmountCents.toLocaleString()} />
          <Row label="Items" value={String(order.itemCount)} />
        </tbody>
      </table>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <tr>
      <td style={{ padding: "0.25rem 1rem 0.25rem 0", fontWeight: 600, color: "#555" }}>{label}</td>
      <td style={{ padding: "0.25rem 0" }}>{value}</td>
    </tr>
  );
}

const buttonStyle: React.CSSProperties = {
  padding: "0.5rem 1rem",
  background: "#1976d2",
  color: "white",
  border: "none",
  borderRadius: 4,
  cursor: "pointer",
};
