import { useState } from 'react';
import { api } from '../api/client.js';
import { money } from '../format.js';

export default function Cart({ cart, reloadCart, reloadProducts, reloadOrders, onError, goToOrders }) {
  const [busy, setBusy] = useState(false);
  const [drafts, setDrafts] = useState({});

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

  const items = cart?.items ?? [];

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>The shared cart</h2>
          {cart && <span className="count">cart #{cart.cartId}</span>}
          <button className="ghost" onClick={() => run(reloadCart)} disabled={busy}>
            Refresh
          </button>
        </div>
        <p className="hint">
          There are no users yet, so the backend keeps exactly one cart row and every endpoint
          operates on it. The total is recomputed by the server from today&apos;s product prices on
          every read — change a price under Products and refresh here to watch it move.
        </p>

        {items.length === 0 ? (
          <p className="empty">The cart is empty. Add something from the Products tab.</p>
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Unit price</th>
                  <th>Quantity</th>
                  <th className="num">Line total</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.productId}>
                    <td>
                      <strong>{item.productName}</strong>
                      <div className="muted small">#{item.productId}</div>
                    </td>
                    <td className="num">{money(item.unitPrice)}</td>
                    <td>
                      <div className="row">
                        <input
                          className="qty"
                          type="number"
                          min="1"
                          value={drafts[item.productId] ?? item.quantity}
                          onChange={(e) =>
                            setDrafts({ ...drafts, [item.productId]: e.target.value })
                          }
                        />
                        <button
                          className="ghost"
                          disabled={busy}
                          onClick={() =>
                            run(async () => {
                              await api.updateCartItem(
                                item.productId,
                                Number(drafts[item.productId] ?? item.quantity),
                              );
                              setDrafts({});
                              await reloadCart();
                            })
                          }
                        >
                          Set
                        </button>
                      </div>
                    </td>
                    <td className="num">{money(item.lineTotal)}</td>
                    <td>
                      <button
                        className="ghost danger"
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            await api.removeCartItem(item.productId);
                            await reloadCart();
                          })
                        }
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan="2" className="muted">
                    {cart.totalItems} item{cart.totalItems === 1 ? '' : 's'}
                  </td>
                  <td />
                  <td className="num total">{money(cart.total)}</td>
                  <td />
                </tr>
              </tfoot>
            </table>

            <div className="checkout">
              <button
                className="primary"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    await api.placeOrder();
                    // Checkout reduces stock, writes the order and clears the cart in one
                    // transaction, so all three views are stale afterwards.
                    await Promise.all([reloadCart(), reloadProducts(), reloadOrders()]);
                    goToOrders();
                  })
                }
              >
                Place order
              </button>
              <span className="hint">
                POST /api/orders — checks stock for every line first, so a single short line leaves
                the cart and all stock untouched.
              </span>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
