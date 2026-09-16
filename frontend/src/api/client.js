// A thin fetch wrapper over the EcomDemo REST API.
//
// Every call is recorded — method, path, status, duration, request and response body — because the
// point of this app is watching what the backend actually returns, not hiding it behind a nice UI.

const listeners = new Set();
let log = [];
let nextId = 1;

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getLog() {
  return log;
}

export function clearLog() {
  log = [];
  listeners.forEach((l) => l(log));
}

function record(entry) {
  // Newest first, and capped: a long session should not grow the page without limit.
  log = [entry, ...log].slice(0, 100);
  listeners.forEach((l) => l(log));
}

/**
 * Thrown for any non-2xx response. `status` and `body` carry what the backend's
 * GlobalExceptionHandler returned, so a caller can show the real message rather than a generic one.
 */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function request(method, path, body) {
  const startedAt = performance.now();
  const entry = {
    id: nextId++,
    method,
    path,
    requestBody: body ?? null,
    at: new Date(),
  };

  let response;
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (networkError) {
    // The proxy could not reach Spring Boot at all - almost always "the backend is not running".
    record({
      ...entry,
      status: 0,
      ok: false,
      durationMs: Math.round(performance.now() - startedAt),
      responseBody: { message: networkError.message },
    });
    throw new ApiError(0, {
      message: 'Could not reach the backend. Is ./mvnw spring-boot:run running on port 8080?',
    });
  }

  // 204 No Content (DELETE) has an empty body; anything else this API returns is JSON.
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = text;
    }
  }

  record({
    ...entry,
    status: response.status,
    ok: response.ok,
    durationMs: Math.round(performance.now() - startedAt),
    responseBody: payload,
  });

  if (!response.ok) {
    throw new ApiError(response.status, payload);
  }
  return payload;
}

export const api = {
  // products
  listProducts: () => request('GET', '/api/products'),
  getProduct: (id) => request('GET', `/api/products/${id}`),
  createProduct: (product) => request('POST', '/api/products', product),
  updateProduct: (id, product) => request('PUT', `/api/products/${id}`, product),
  deleteProduct: (id) => request('DELETE', `/api/products/${id}`),

  // cart - every mutating endpoint returns the whole cart, so there is never a re-fetch
  getCart: () => request('GET', '/api/cart'),
  addCartItem: (productId, quantity) => request('POST', '/api/cart/items', { productId, quantity }),
  updateCartItem: (productId, quantity) =>
    request('PUT', `/api/cart/items/${productId}`, { quantity }),
  removeCartItem: (productId) => request('DELETE', `/api/cart/items/${productId}`),

  // orders
  placeOrder: () => request('POST', '/api/orders'),
  listOrders: () => request('GET', '/api/orders'),
  getOrder: (id) => request('GET', `/api/orders/${id}`),

  // Escape hatch for the scenario buttons, which deliberately send invalid bodies.
  raw: (method, path, body) => request(method, path, body),
};
