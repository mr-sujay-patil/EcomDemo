import { useState } from 'react';
import { api } from '../api/client.js';
import { money } from '../format.js';

const EMPTY_FORM = { name: '', description: '', price: '', stockQuantity: '' };

export default function Products({ products, reloadProducts, reloadCart, onError }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [editingId, setEditingId] = useState(null);
  const [quantities, setQuantities] = useState({});
  const [busy, setBusy] = useState(false);

  const field = (key) => ({
    value: form[key],
    onChange: (e) => setForm({ ...form, [key]: e.target.value }),
  });

  // The request record is intentionally not validated in the browser: sending a blank name is a
  // useful way to see the backend's 400 and exactly which field it names.
  const toRequest = () => ({
    name: form.name,
    description: form.description,
    price: form.price === '' ? null : Number(form.price),
    stockQuantity: form.stockQuantity === '' ? null : Number(form.stockQuantity),
  });

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

  const submit = (e) => {
    e.preventDefault();
    run(async () => {
      if (editingId === null) {
        await api.createProduct(toRequest());
      } else {
        await api.updateProduct(editingId, toRequest());
      }
      setForm(EMPTY_FORM);
      setEditingId(null);
      await reloadProducts();
    });
  };

  const startEdit = (product) => {
    setEditingId(product.id);
    setForm({
      name: product.name,
      description: product.description ?? '',
      price: String(product.price),
      stockQuantity: String(product.stockQuantity),
    });
  };

  const cancelEdit = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
  };

  return (
    <div className="stack">
      <section className="card">
        <h2>{editingId === null ? 'Create a product' : `Replace product #${editingId}`}</h2>
        <p className="hint">
          {editingId === null
            ? 'POST /api/products → 201'
            : 'PUT /api/products/{id} → 200. PUT replaces the whole record, so every field is sent.'}
        </p>
        <form className="form-grid" onSubmit={submit}>
          <label>
            Name
            <input {...field('name')} placeholder="Mechanical Keyboard" />
          </label>
          <label>
            Description
            <input {...field('description')} placeholder="Hot-swappable 75%…" />
          </label>
          <label>
            Price
            <input {...field('price')} type="number" step="0.01" placeholder="129.99" />
          </label>
          <label>
            Stock
            <input {...field('stockQuantity')} type="number" placeholder="40" />
          </label>
          <div className="form-actions">
            <button type="submit" disabled={busy}>
              {editingId === null ? 'Create' : 'Save'}
            </button>
            {editingId !== null && (
              <button type="button" className="ghost" onClick={cancelEdit}>
                Cancel
              </button>
            )}
            <button type="button" className="ghost" onClick={() => setForm(EMPTY_FORM)}>
              Clear
            </button>
          </div>
        </form>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Catalogue</h2>
          <span className="count">{products.length} products</span>
          <button className="ghost" onClick={() => run(reloadProducts)} disabled={busy}>
            Refresh
          </button>
        </div>

        {products.length === 0 ? (
          <p className="empty">
            No products. Either the catalogue has not been seeded yet, or the backend is not
            running — the request log on the right will say which.
          </p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Id</th>
                <th>Name</th>
                <th className="num">Price</th>
                <th className="num">Stock</th>
                <th>Add to cart</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {products.map((product) => (
                <tr key={product.id} className={editingId === product.id ? 'editing' : undefined}>
                  <td className="muted">{product.id}</td>
                  <td>
                    <strong>{product.name}</strong>
                    <div className="muted small">{product.description}</div>
                  </td>
                  <td className="num">{money(product.price)}</td>
                  <td className="num">
                    <span className={product.stockQuantity === 0 ? 'out' : undefined}>
                      {product.stockQuantity}
                    </span>
                  </td>
                  <td>
                    <div className="row">
                      <input
                        className="qty"
                        type="number"
                        min="1"
                        value={quantities[product.id] ?? 1}
                        onChange={(e) =>
                          setQuantities({ ...quantities, [product.id]: e.target.value })
                        }
                      />
                      <button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            await api.addCartItem(product.id, Number(quantities[product.id] ?? 1));
                            await reloadCart();
                          })
                        }
                      >
                        Add
                      </button>
                    </div>
                  </td>
                  <td className="row">
                    <button className="ghost" onClick={() => startEdit(product)}>
                      Edit
                    </button>
                    <button
                      className="ghost danger"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await api.deleteProduct(product.id);
                          if (editingId === product.id) cancelEdit();
                          await reloadProducts();
                        })
                      }
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
