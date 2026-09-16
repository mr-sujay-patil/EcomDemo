import { useState } from 'react';
import { api } from '../api/client.js';
import { money, timestamp } from '../format.js';
import Json from './Json.jsx';

export default function Orders({ orders, reloadOrders, onError }) {
  const [openId, setOpenId] = useState(null);
  const [lookupId, setLookupId] = useState('');
  const [lookup, setLookup] = useState(null);
  const [busy, setBusy] = useState(false);

  async function run(action) {
    setBusy(true);
    try {
      await action();
      onError(null);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>Orders</h2>
          <span className="count">{orders.length} placed</span>
          <button className="ghost" onClick={() => run(reloadOrders)} disabled={busy}>
            Refresh
          </button>
        </div>
        <p className="hint">
          An order is a historical record: each line stores the product name and unit price as they
          were at checkout. Rename or delete the product afterwards and these lines stay exactly as
          the customer saw them.
        </p>

        {orders.length === 0 ? (
          <p className="empty">No orders yet. Fill the cart and place one.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Id</th>
                <th>Placed at</th>
                <th className="num">Lines</th>
                <th className="num">Total</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.id}>
                  <td className="muted">{order.id}</td>
                  <td>{timestamp(order.placedAt)}</td>
                  <td className="num">{order.items.length}</td>
                  <td className="num total">{money(order.totalAmount)}</td>
                  <td>
                    <button
                      className="ghost"
                      onClick={() => setOpenId(openId === order.id ? null : order.id)}
                    >
                      {openId === order.id ? 'Hide' : 'Lines'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {openId !== null && (
          <div className="detail">
            <h3>Order #{openId}</h3>
            <table>
              <thead>
                <tr>
                  <th>Product (snapshot)</th>
                  <th className="num">Unit price</th>
                  <th className="num">Qty</th>
                  <th className="num">Line total</th>
                </tr>
              </thead>
              <tbody>
                {orders
                  .find((o) => o.id === openId)
                  ?.items.map((item, index) => (
                    <tr key={index}>
                      <td>
                        {item.productName}
                        <span className="muted small"> #{item.productId}</span>
                      </td>
                      <td className="num">{money(item.unitPrice)}</td>
                      <td className="num">{item.quantity}</td>
                      <td className="num">{money(item.lineTotal)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h2>Fetch one order</h2>
        <p className="hint">GET /api/orders/{'{id}'} — an unknown id is a 404 from the same handler.</p>
        <div className="row">
          <input
            className="qty wide"
            placeholder="order id"
            value={lookupId}
            onChange={(e) => setLookupId(e.target.value)}
          />
          <button
            disabled={busy || lookupId === ''}
            onClick={() =>
              run(async () => {
                setLookup(null);
                setLookup(await api.getOrder(lookupId));
              })
            }
          >
            Fetch
          </button>
        </div>
        {lookup && <Json value={lookup} />}
      </section>
    </div>
  );
}
