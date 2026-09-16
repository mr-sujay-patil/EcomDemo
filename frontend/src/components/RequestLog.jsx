import { useEffect, useState } from 'react';
import { subscribe, getLog, clearLog } from '../api/client.js';
import { clock } from '../format.js';
import Json from './Json.jsx';
import StatusBadge from './StatusBadge.jsx';

export default function RequestLog() {
  const [log, setLog] = useState(getLog);
  const [openId, setOpenId] = useState(null);

  useEffect(() => subscribe(setLog), []);

  return (
    <aside className="log">
      <div className="log-head">
        <h2>Requests</h2>
        <button className="ghost" onClick={clearLog} disabled={log.length === 0}>
          Clear
        </button>
      </div>

      {log.length === 0 ? (
        <p className="empty">Every call this page makes shows up here — status, timing and body.</p>
      ) : (
        <ul className="log-list">
          {log.map((entry) => (
            <li key={entry.id} className={entry.ok ? undefined : 'failed'}>
              <button className="log-row" onClick={() => setOpenId(openId === entry.id ? null : entry.id)}>
                <StatusBadge status={entry.status} />
                <span className="method">{entry.method}</span>
                <span className="path">{entry.path}</span>
                <span className="muted small">{entry.durationMs}ms</span>
              </button>
              {openId === entry.id && (
                <div className="log-detail">
                  <div className="muted small">{clock(entry.at)}</div>
                  {entry.requestBody && (
                    <>
                      <div className="label">request</div>
                      <Json value={entry.requestBody} />
                    </>
                  )}
                  <div className="label">response</div>
                  <Json value={entry.responseBody} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
