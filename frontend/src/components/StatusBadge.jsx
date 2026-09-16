// 0 means the request never left the browser - the proxy could not reach Spring Boot.
function toneFor(status) {
  if (status === 0) return 'dead';
  if (status < 300) return 'ok';
  if (status < 500) return 'warn';
  return 'bad';
}

export default function StatusBadge({ status }) {
  return <span className={`badge badge-${toneFor(status)}`}>{status === 0 ? 'ERR' : status}</span>;
}
