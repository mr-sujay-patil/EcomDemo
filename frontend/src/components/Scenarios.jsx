import { useState } from 'react';
import { api, ApiError } from '../api/client.js';
import Json from './Json.jsx';
import StatusBadge from './StatusBadge.jsx';

// The failure paths are the interesting half of this API, and they are tedious to reach by hand.
// Each scenario states the status it expects, so a run either confirms the contract or shows
// exactly where it drifted.
const SCENARIOS = [
  {
    id: 'not-found',
    title: 'Unknown product → 404',
    detail: 'GET /api/products/999999',
    expect: 404,
    run: () => api.raw('GET', '/api/products/999999'),
  },
  {
    id: 'validation',
    title: 'Invalid product body → 400',
    detail: 'POST /api/products with a blank name and a negative price. The message names the fields.',
    expect: 400,
    run: () =>
      api.raw('POST', '/api/products', { name: '', description: '', price: -1, stockQuantity: -5 }),
  },
  {
    id: 'unreadable',
    title: 'Wrong type in the path → 400',
    detail: 'GET /api/products/not-a-number — a binding failure, not a validation one.',
    expect: 400,
    run: () => api.raw('GET', '/api/products/not-a-number'),
  },
  {
    id: 'stock',
    title: 'More than stock → 409',
    detail: 'POST /api/cart/items with quantity 99999. The request is valid; the state forbids it.',
    expect: 409,
    run: () => api.raw('POST', '/api/cart/items', { productId: 1, quantity: 99999 }),
  },
  {
    id: 'empty-cart',
    title: 'Checkout an empty cart → 409',
    detail: 'POST /api/orders with nothing in the cart. Empty the cart first for a true result.',
    expect: 409,
    run: () => api.raw('POST', '/api/orders'),
  },
  {
    id: 'missing-line',
    title: 'Update a line that is not there → 404',
    detail: 'PUT /api/cart/items/999999 with a valid body.',
    expect: 404,
    run: () => api.raw('PUT', '/api/cart/items/999999', { quantity: 1 }),
  },
];

export default function Scenarios({ reloadAll }) {
  const [results, setResults] = useState({});
  const [busy, setBusy] = useState(false);

  async function runOne(scenario) {
    try {
      const body = await scenario.run();
      return { status: 200, body, pass: scenario.expect === 200 };
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      const body = e instanceof ApiError ? e.body : { message: e.message };
      return { status, body, pass: status === scenario.expect };
    }
  }

  async function run(scenario) {
    setBusy(true);
    const result = await runOne(scenario);
    setResults((r) => ({ ...r, [scenario.id]: result }));
    setBusy(false);
  }

  async function runAll() {
    setBusy(true);
    const next = {};
    // Sequential on purpose: these share one cart, and interleaving them would make the
    // stock and empty-cart scenarios depend on each other's timing.
    for (const scenario of SCENARIOS) {
      next[scenario.id] = await runOne(scenario);
    }
    setResults(next);
    setBusy(false);
    await reloadAll();
  }

  const ran = Object.keys(results).length;
  const passed = Object.values(results).filter((r) => r.pass).length;

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>Error paths</h2>
          {ran > 0 && (
            <span className={`count ${passed === ran ? 'pass' : 'fail'}`}>
              {passed}/{ran} as expected
            </span>
          )}
          <button className="primary" onClick={runAll} disabled={busy}>
            Run all
          </button>
        </div>
        <p className="hint">
          400 means &ldquo;fix your request&rdquo;; 409 means &ldquo;your request is fine, but the
          server&apos;s state forbids it&rdquo;. Every one of these should come back as
          <code> {'{ status, message }'}</code> from the same handler.
        </p>

        <ul className="scenarios">
          {SCENARIOS.map((scenario) => {
            const result = results[scenario.id];
            return (
              <li key={scenario.id}>
                <div className="scenario-head">
                  <div>
                    <strong>{scenario.title}</strong>
                    <div className="muted small">{scenario.detail}</div>
                  </div>
                  <div className="row">
                    {result && (
                      <>
                        <StatusBadge status={result.status} />
                        <span className={result.pass ? 'pass' : 'fail'}>
                          {result.pass ? 'as expected' : `expected ${scenario.expect}`}
                        </span>
                      </>
                    )}
                    <button className="ghost" onClick={() => run(scenario)} disabled={busy}>
                      Run
                    </button>
                  </div>
                </div>
                {result && <Json value={result.body} />}
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}
