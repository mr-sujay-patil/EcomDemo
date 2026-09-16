import { useCallback, useEffect, useState } from 'react';
import { api } from './api/client.js';
import Products from './components/Products.jsx';
import Cart from './components/Cart.jsx';
import Orders from './components/Orders.jsx';
import Scenarios from './components/Scenarios.jsx';
import RequestLog from './components/RequestLog.jsx';

const TABS = [
  { id: 'products', label: 'Products' },
  { id: 'cart', label: 'Cart' },
  { id: 'orders', label: 'Orders' },
  { id: 'scenarios', label: 'Error paths' },
];

export default function App() {
  const [tab, setTab] = useState('products');
  const [products, setProducts] = useState([]);
  const [cart, setCart] = useState(null);
  const [orders, setOrders] = useState([]);
  const [error, setError] = useState(null);
  const [online, setOnline] = useState(null); // null = not yet known

  const reloadProducts = useCallback(async () => setProducts(await api.listProducts()), []);
  const reloadCart = useCallback(async () => setCart(await api.getCart()), []);
  const reloadOrders = useCallback(async () => setOrders(await api.listOrders()), []);

  const reloadAll = useCallback(async () => {
    try {
      // The cart is fetched first: a failure here is the cheapest signal that the backend is down,
      // and it is the one endpoint that always exists (the service recreates the row if missing).
      await reloadCart();
      await Promise.all([reloadProducts(), reloadOrders()]);
      setOnline(true);
      setError(null);
    } catch (e) {
      setOnline(e.status !== 0);
      setError(e);
    }
  }, [reloadCart, reloadProducts, reloadOrders]);

  useEffect(() => {
    reloadAll();
  }, [reloadAll]);

  return (
    <div className="app">
      <header className="top">
        <div>
          <h1>EcomDemo API Console</h1>
          <p className="muted small">
            A throwaway UI for exercising the backend by hand. Calls go to <code>/api/*</code>, which
            Vite proxies to <code>localhost:8080</code>.
          </p>
        </div>
        <div className="row">
          <span className={`dot ${online === null ? 'unknown' : online ? 'up' : 'down'}`} />
          <span className="small">
            {online === null ? 'checking…' : online ? 'backend reachable' : 'backend unreachable'}
          </span>
          <button className="ghost" onClick={reloadAll}>
            Reload all
          </button>
        </div>
      </header>

      {online === false && (
        <div className="banner">
          <strong>Nothing is answering on port 8080.</strong> Start the backend in the project root:
          <code>./mvnw spring-boot:run</code> — it needs PostgreSQL running as well.
        </div>
      )}

      {error && online !== false && (
        <div className="banner warn">
          <strong>{error.status}</strong> {error.message}
          <button className="ghost" onClick={() => setError(null)}>
            Dismiss
          </button>
        </div>
      )}

      <div className="body">
        <main>
          <nav className="tabs">
            {TABS.map((t) => (
              <button
                key={t.id}
                className={tab === t.id ? 'tab active' : 'tab'}
                onClick={() => setTab(t.id)}
              >
                {t.label}
                {t.id === 'cart' && cart?.totalItems > 0 && (
                  <span className="pill">{cart.totalItems}</span>
                )}
                {t.id === 'orders' && orders.length > 0 && <span className="pill">{orders.length}</span>}
              </button>
            ))}
          </nav>

          {tab === 'products' && (
            <Products
              products={products}
              reloadProducts={reloadProducts}
              reloadCart={reloadCart}
              onError={setError}
            />
          )}
          {tab === 'cart' && (
            <Cart
              cart={cart}
              reloadCart={reloadCart}
              reloadProducts={reloadProducts}
              reloadOrders={reloadOrders}
              onError={setError}
              goToOrders={() => setTab('orders')}
            />
          )}
          {tab === 'orders' && (
            <Orders orders={orders} reloadOrders={reloadOrders} onError={setError} />
          )}
          {tab === 'scenarios' && <Scenarios reloadAll={reloadAll} />}
        </main>

        <RequestLog />
      </div>
    </div>
  );
}
