import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowUpRight,
  Check,
  LockKeyhole,
  LogOut,
  Monitor,
  Moon,
  RefreshCw,
  ShieldCheck,
  SlidersHorizontal,
  Sun,
  UserRound,
  X,
} from 'lucide-react';
import { api } from './api';
import { Empty, Loading } from './components';
import type { User } from './types';
import { useModalFocus } from './useModalFocus';
import { useTheme, type ThemePreference } from './theme';

export function initials(name: string) {
  return (
    name
      .trim()
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => Array.from(part)[0])
      .join('')
      .toUpperCase() || 'U'
  );
}

const tabs = [
  { id: 'account', label: 'Account', icon: UserRound },
  { id: 'preferences', label: 'Preferences', icon: SlidersHorizontal },
  { id: 'access', label: 'Access', icon: ShieldCheck },
] as const;
const themes: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Day', icon: Sun },
  { value: 'dark', label: 'Night', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
];

export function UserProfile({
  user,
  onClose,
  onSignOut,
  onAudit,
}: {
  user: User;
  onClose: () => void;
  onSignOut: () => void;
  onAudit: () => void;
}) {
  useModalFocus('User profile', onClose);
  const [tab, setTab] = useState<(typeof tabs)[number]['id']>('account');
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const id = useId();
  const { preference, setPreference } = useTheme();
  const profile = useQuery<{ email: string; tenantId: string; roles: string[] }>({
    queryKey: ['profile', user.tenantId, user.email],
    queryFn: () => api('/auth/me'),
    staleTime: 60000,
  });
  const roles = profile.data?.roles || [];
  const admin = roles.includes('ADMIN');
  const engineer = admin || roles.includes('ENGINEER');
  const permissions = [
    { label: 'View fleet data and reports', allowed: engineer || roles.includes('VIEWER') },
    { label: 'Inspect raw telemetry', allowed: engineer },
    { label: 'Approve mappings and replay', allowed: engineer },
    { label: 'Erase vehicle evidence', allowed: admin },
  ];
  function changeTab(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    setTab(tabs[next].id);
    tabRefs.current[next]?.focus();
  }
  return (
    <div className="drawer-overlay" onClick={onClose}>
      <aside
        className="profile-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="User profile"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="profile-drawer-header">
          <div>
            <UserRound size={18} />
            <h2>User profile</h2>
          </div>
          <button
            className="icon-button"
            aria-label="Close user profile"
            title="Close profile"
            onClick={onClose}
          >
            <X size={19} />
          </button>
        </header>
        <div className="profile-identity">
          <span className="avatar profile-avatar" aria-hidden="true">
            {initials(user.name)}
          </span>
          <div>
            <h3>{user.name}</h3>
            <p>{profile.data?.email || user.email}</p>
            <span className="identity-status">
              <span className="status-dot green-bg" />
              Signed in
            </span>
          </div>
        </div>
        <div className="profile-tabs" role="tablist" aria-label="Profile sections">
          {tabs.map(({ id: value, label, icon: Icon }, index) => (
            <button
              key={value}
              ref={(element) => {
                tabRefs.current[index] = element;
              }}
              id={`${id}-${value}`}
              role="tab"
              aria-selected={tab === value}
              aria-controls={`${id}-panel`}
              tabIndex={tab === value ? 0 : -1}
              onKeyDown={(event) => changeTab(event, index)}
              onClick={() => setTab(value)}
            >
              <Icon size={15} />
              {label}
            </button>
          ))}
        </div>
        <div
          className="profile-panel"
          role="tabpanel"
          id={`${id}-panel`}
          aria-labelledby={`${id}-${tab}`}
          tabIndex={0}
        >
          {tab === 'preferences' ? (
            <>
              <fieldset className="appearance-settings">
                <legend>Appearance</legend>
                <div className="theme-options">
                  {themes.map(({ value, label, icon: Icon }) => (
                    <label className="theme-option" key={value}>
                      <input
                        type="radio"
                        name={`${id}-appearance`}
                        value={value}
                        checked={preference === value}
                        onChange={() => setPreference(value)}
                      />
                      <span>
                        <Icon size={22} />
                        <strong>{label}</strong>
                        <Check className="theme-choice-check" size={13} />
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <section className="profile-section">
                <h3>Regional settings</h3>
                <dl className="account-details">
                  <div>
                    <dt>Time zone</dt>
                    <dd>{Intl.DateTimeFormat().resolvedOptions().timeZone}</dd>
                  </div>
                  <div>
                    <dt>Browser language</dt>
                    <dd>{navigator.language}</dd>
                  </div>
                  <div>
                    <dt>Telemetry units</dt>
                    <dd>km/h, km, %</dd>
                  </div>
                </dl>
              </section>
            </>
          ) : profile.isPending ? (
            <Loading label="Loading account" />
          ) : profile.isError ? (
            <Empty title="Account unavailable" detail={profile.error.message}>
              <button className="button secondary" onClick={() => profile.refetch()}>
                <RefreshCw size={15} />
                Retry
              </button>
            </Empty>
          ) : tab === 'account' ? (
            <section className="profile-section">
              <h3>Account details</h3>
              <dl className="account-details">
                <div>
                  <dt>Display name</dt>
                  <dd>{user.name}</dd>
                </div>
                <div>
                  <dt>Email / account ID</dt>
                  <dd>{profile.data.email}</dd>
                </div>
                <div>
                  <dt>Role</dt>
                  <dd className="capitalize">{roles.map((role) => role.toLowerCase()).join(', ')}</dd>
                </div>
                <div>
                  <dt>Tenant</dt>
                  <dd className="mono">{profile.data.tenantId}</dd>
                </div>
              </dl>
              <div className="account-security">
                <ShieldCheck size={17} />
                <span>Tenant-scoped access</span>
              </div>
            </section>
          ) : (
            <section className="profile-section">
              <h3>Workspace permissions</h3>
              <ul className="permission-list">
                {permissions.map(({ label, allowed }) => (
                  <li key={label}>
                    <span>{label}</span>
                    <strong className={allowed ? 'permission-allowed' : 'permission-restricted'}>
                      {allowed ? <Check size={14} /> : <LockKeyhole size={14} />}
                      {allowed ? 'Allowed' : 'Restricted'}
                    </strong>
                  </li>
                ))}
              </ul>
              <button className="text-button profile-audit" onClick={onAudit}>
                Open audit trail
                <ArrowUpRight size={15} />
              </button>
            </section>
          )}
        </div>
        <footer className="profile-drawer-footer">
          <span>
            <LockKeyhole size={14} />
            Secure workspace
          </span>
          <button className="button secondary" onClick={onSignOut}>
            <LogOut size={15} />
            Sign out of account
          </button>
        </footer>
      </aside>
    </div>
  );
}
