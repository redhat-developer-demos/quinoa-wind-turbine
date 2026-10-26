/**
 * Admin theme control. Every call goes through the dashboard's operator session, and each returns
 * { ok, errors } so the panel can show why the server kept the current theme (422 validation,
 * 503 AI disabled, 502 unusable model output).
 */
async function post(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  if (response.status === 204) {
    return { ok: true };
  }
  const payload = await response.json().catch(() => null);
  return { ok: response.ok, errors: (payload && payload.errors) || [] };
}

export function fetchPresets() {
  return fetch('/api/theme/presets', { headers: { Accept: 'application/json' } })
    .then((r) => (r.ok ? r.json() : []))
    .catch(() => []);
}

export function applyPreset(id) {
  return post(`/api/theme/preset/${id}`);
}

export function applyTheme(theme) {
  return post('/api/theme', theme);
}

export function suggestNames(prompt) {
  return post('/api/theme/suggest-names', { prompt });
}

export function generateTheme(prompt) {
  return post('/api/theme/generate', { prompt });
}
