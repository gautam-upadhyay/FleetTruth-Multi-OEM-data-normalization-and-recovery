import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Moon, Sun } from 'lucide-react';

export type ThemePreference = 'light' | 'dark' | 'system';
const storageKey = 'fleettruth-theme';
const mediaQuery = '(prefers-color-scheme: dark)';
const ThemeContext = createContext<{
  preference: ThemePreference;
  resolved: 'light' | 'dark';
  setPreference: (value: ThemePreference) => void;
} | null>(null);

function readPreference(): ThemePreference {
  try {
    const saved = localStorage.getItem(storageKey);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return 'system';
  }
}

function applyTheme(preference: ThemePreference, systemDark: boolean) {
  const resolved = preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;
  document.documentElement.dataset.theme = resolved;
  document.documentElement.style.colorScheme = resolved;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', resolved === 'dark' ? '#131819' : '#f4f6f5');
  return resolved;
}

export function initializeTheme() {
  applyTheme(readPreference(), window.matchMedia(mediaQuery).matches);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setValue] = useState<ThemePreference>(readPreference);
  const [systemDark, setSystemDark] = useState(() => window.matchMedia(mediaQuery).matches);
  const resolved = preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;
  useEffect(() => {
    const media = window.matchMedia(mediaQuery);
    const changed = () => setSystemDark(media.matches);
    const storageChanged = (event: StorageEvent) => {
      if (event.key === storageKey || event.key === null) setValue(readPreference());
    };
    media.addEventListener('change', changed);
    window.addEventListener('storage', storageChanged);
    return () => {
      media.removeEventListener('change', changed);
      window.removeEventListener('storage', storageChanged);
    };
  }, []);
  useEffect(() => {
    applyTheme(preference, systemDark);
  }, [preference, systemDark]);
  const setPreference = (value: ThemePreference) => {
    setValue(value);
    try {
      localStorage.setItem(storageKey, value);
    } catch {
      // Theme switching remains available when browser storage is restricted.
    }
  };
  return (
    <ThemeContext.Provider value={{ preference, resolved, setPreference }}>{children}</ThemeContext.Provider>
  );
}

export function useTheme() {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error('ThemeProvider is required');
  return theme;
}

export function ThemeToggle() {
  const { resolved, setPreference } = useTheme();
  const label = resolved === 'dark' ? 'Switch to day mode' : 'Switch to night mode';
  return (
    <button
      className="icon-button theme-toggle"
      aria-label={label}
      title={label}
      onClick={() => setPreference(resolved === 'dark' ? 'light' : 'dark')}
    >
      {resolved === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
    </button>
  );
}
