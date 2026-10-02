import type { User } from './types';
export function session(): { token: string; user: User } | null {
  try {
    return JSON.parse(sessionStorage.getItem('fleettruth-session') || 'null');
  } catch {
    return null;
  }
}
export function saveSession(value: unknown) {
  sessionStorage.setItem('fleettruth-session', JSON.stringify(value));
}
export function clearSession() {
  sessionStorage.removeItem('fleettruth-session');
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = session()?.token;
  const response = await fetch('/api' + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const result = await response.json();
      message = result.error || result.message || message;
    } catch {
      /* Response can be empty on authentication failure. */
    }
    if (response.status === 401 && path != '/auth/login')
      window.dispatchEvent(new Event('fleettruth-expired'));
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}
export const post = <T>(path: string, body: unknown = {}) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body) });
export function download(name: string, data: unknown) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}
