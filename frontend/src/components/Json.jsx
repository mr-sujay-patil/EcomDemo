export default function Json({ value }) {
  if (value === null || value === undefined) return <pre className="json muted">(no body)</pre>;
  return <pre className="json">{typeof value === 'string' ? value : JSON.stringify(value, null, 2)}</pre>;
}
