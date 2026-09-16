// Prices arrive as JSON numbers (Jackson serialises BigDecimal that way), so formatting is
// presentation only - no arithmetic is ever done on them here. The server owns every total.
export const money = (value) =>
  value === null || value === undefined
    ? '—'
    : new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(value);

export const timestamp = (iso) => (iso ? new Date(iso).toLocaleString() : '—');

export const clock = (date) =>
  date.toLocaleTimeString(undefined, { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
