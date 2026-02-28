import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "AI-ADAM Demo",
  description: "Demo frontend for AI-ADAM generated API — PII-safe DTOs only",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body style={{ fontFamily: "system-ui, sans-serif", margin: 0, padding: "2rem", background: "#f8f9fa" }}>
        <header style={{ marginBottom: "2rem" }}>
          <h1 style={{ margin: 0, fontSize: "1.5rem" }}>AI-ADAM Demo</h1>
          <p style={{ color: "#666", margin: "0.25rem 0 0" }}>
            Generated REST API frontend — only PII-safe fields are visible
          </p>
        </header>
        {children}
      </body>
    </html>
  );
}
