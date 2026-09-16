import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

// Deliberately not wrapped in <StrictMode>. StrictMode double-invokes effects in development, which
// would fire the initial load twice and put a duplicate of every startup request in the log - and
// the log is the whole point of this app. Correctness of the log beats the extra dev warnings here.
createRoot(document.getElementById('root')).render(<App />);
