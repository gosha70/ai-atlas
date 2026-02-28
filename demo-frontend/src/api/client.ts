/**
 * AI-ADAM Demo API client — generated from OpenAPI spec.
 * Communicates with the Spring Boot demo backend via /api/v1/ endpoints.
 */

const BASE_URL = "/api/v1/order-service";

export interface OrderDto {
  id: number;
  status: string;
  totalAmountCents: number;
  itemCount: number;
}

export async function findById(id: number): Promise<OrderDto> {
  const response = await fetch(`${BASE_URL}/find-by-id?id=${id}`, {
    method: "POST",
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch order: ${response.status}`);
  }
  return response.json();
}

export async function findByStatus(status: string): Promise<OrderDto[]> {
  const response = await fetch(
    `${BASE_URL}/find-by-status?status=${encodeURIComponent(status)}`,
    { method: "POST" }
  );
  if (!response.ok) {
    throw new Error(`Failed to fetch orders: ${response.status}`);
  }
  return response.json();
}
