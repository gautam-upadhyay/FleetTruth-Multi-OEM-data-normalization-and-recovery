import { useEffect, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Bell,
  Check,
  ChevronRight,
  Database,
  GitBranch,
  LayoutDashboard,
  Loader2,
  LogOut,
  Menu,
  MoreHorizontal,
  Pause,
  Play,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  Truck,
  Workflow,
  X,
  Zap,
  Download,
  Radio,
  AlertTriangle,
  FileClock,
  ArrowUp,
} from 'lucide-react';
import { api, clearSession, download, post, saveSession, session } from './api';
import {
  Badge,
  Brand,
  Empty,
  Loading,
  number,
  PageHeading,
  relative,
  SectionTitle,
  SignalChart,
  LinkButton,
} from './components';
import { WorkspaceContext, useWorkspace } from './context';
import type { AssistantReply, Audit, Overview, Simulation, User, View } from './types';
import {
  VehiclesPage,
  VehicleDrawer,
  AlertsPage,
  IntegrationsPage,
  MappingsPage,
  RecoveryPage,
  AnalyticsPage,
  AuditPage,
} from './pages';

import { DriftPanel, PrivacyPanel } from './Governance';
import { useModalFocus } from './useModalFocus';
import './governance.css';
import { EnterpriseGate } from './EnterpriseLogin';
import { ThemeToggle } from './theme';
import { initials, UserProfile } from './UserProfile';

const nav: { view: View; label: string; icon: typeof Activity }[] = [
  { view: 'overview', label: 'Overview', icon: LayoutDashboard },
  { view: 'vehicles', label: 'Vehicles', icon: Truck },
  { view: 'alerts', label: 'Alerts', icon: Bell },
  { view: 'integrations', label: 'OEM integrations', icon: Workflow },
  { view: 'mappings', label: 'Mapping studio', icon: GitBranch },
  { view: 'recovery', label: 'Recovery', icon: RefreshCw },
  { view: 'analytics', label: 'Analytics', icon: Activity },
  { view: 'audit', label: 'Audit trail', icon: FileClock },
];
function route() {
  const [view, target] = (location.hash.replace('#/', '') || 'overview').split('?');
  return {
    view: nav.some((n) => n.view === view) ? (view as View) : ('overview' as View),
    target: new URLSearchParams(target).get('id') || '',
  };
}
export default function App() {
  const [auth, setAuth] = useState(session);
  const [locationState, setLocationState] = useState(route);
  const [sidebar, setSidebar] = useState(false);
  const [assistant, setAssistant] = useState(false);
  const [profile, setProfile] = useState(false);
  const [vehicle, setVehicle] = useState('');
  const [search, setSearch] = useState('');
  const [toast, setToast] = useState<{ text: string; error: boolean } | null>(null);
  const client = useQueryClient();
  const overview = useQuery<Overview>({
    queryKey: ['overview'],
    queryFn: () => api('/overview'),
    enabled: !!auth,
    refetchInterval: 2000,
  });
  const simulation = useQuery<Simulation>({
    queryKey: ['simulation'],
    queryFn: () => api('/simulation'),
    enabled: !!auth,
    refetchInterval: 5000,
  });
  useEffect(() => {
    const changed = () => setLocationState(route());
    const expired = () => {
      clearSession();
      setAuth(null);
      setProfile(false);
      setAssistant(false);
      setVehicle('');
      client.clear();
    };
    window.addEventListener('hashchange', changed);
    window.addEventListener('fleettruth-expired', expired);
    return () => {
      window.removeEventListener('hashchange', changed);
      window.removeEventListener('fleettruth-expired', expired);
    };
  }, [client]);
  useEffect(() => {
    if (toast) {
      const timer = setTimeout(() => setToast(null), 4500);
      return () => clearTimeout(timer);
    }
  }, [toast]);
  const notify = (text: string, error = false) => setToast({ text, error });
  const navigate = (view: View, target = '') => {
    location.hash = `/${view}${target ? '?id=' + encodeURIComponent(target) : ''}`;
    setSidebar(false);
  };
  const signOut = () => {
    clearSession();
    setAuth(null);
    setProfile(false);
    setAssistant(false);
    setVehicle('');
    setSidebar(false);
    client.clear();
  };
  const openProfile = () => {
    setAssistant(false);
    setVehicle('');
    setProfile(true);
  };
  if (!auth)
    return (
      <>
        <div className="login-appearance">
          <ThemeToggle />
        </div>
        <EnterpriseGate
          onLogin={(data) => {
            saveSession(data);
            setAuth(data);
          }}
          demo={
            <Login
              onLogin={(data) => {
                saveSession(data);
                setAuth(data);
              }}
            />
          }
        />
      </>
    );
  const canEdit = auth.user.role !== 'VIEWER';
  const title = nav.find((n) => n.view === locationState.view)?.label || 'Overview';
  async function toggleSimulation() {
    try {
      await post('/simulation', { running: !simulation.data?.running });
      client.invalidateQueries({ queryKey: ['simulation'] });
      notify(simulation.data?.running ? 'Simulation paused' : 'Simulation resumed');
    } catch (e) {
      notify((e as Error).message, true);
    }
  }
  return (
    <WorkspaceContext.Provider value={{ user: auth.user, navigate, notify, canEdit }}>
      <div className="app-shell">
        {sidebar && <div className="mobile-scrim" onClick={() => setSidebar(false)} />}
        <aside className={`sidebar ${sidebar ? 'open' : ''}`}>
          <a href="#/overview" className="brand-link" aria-label="FleetTruth overview">
            <Brand />
          </a>
          <div className="workspace-selector">
            <span className="workspace-logo">M</span>
            <span>
              <strong>Meridian Logistics</strong>
              <small>Fleet operations</small>
            </span>
          </div>
          <span className="nav-caption">WORKSPACE</span>
          <nav>
            {nav.map(({ view, label, icon: Icon }, i) => (
              <div key={view}>
                {i === 3 && <span className="nav-caption middle">DATA & INTELLIGENCE</span>}
                <button
                  className={`nav-item ${locationState.view === view ? 'active' : ''}`}
                  aria-current={locationState.view === view ? 'page' : undefined}
                  onClick={() => navigate(view)}
                >
                  <Icon size={18} />
                  <span>{label}</span>
                  {view === 'alerts' && !!overview.data?.openAlerts && (
                    <b className="nav-count">{overview.data.openAlerts}</b>
                  )}
                  {view === 'integrations' && !!overview.data?.openIncidents && (
                    <span className="nav-attention" />
                  )}
                </button>
              </div>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <button className="assistant-nav" onClick={() => setAssistant(true)}>
              <Sparkles size={18} />
              <span>Fleet assistant</span>
              <ArrowUpRight size={15} />
            </button>
            <div className="system-status">
              <span className="live-dot" />
              <span>Synthetic fleet connected</span>
            </div>
            <div className="profile">
              <button
                className="profile-launcher"
                aria-label="Open account profile"
                title="User profile"
                aria-haspopup="dialog"
                aria-expanded={profile}
                onClick={openProfile}
              >
                <span className="avatar" aria-hidden="true">
                  {initials(auth.user.name)}
                </span>
                <span className="profile-summary">
                  <strong>{auth.user.name}</strong>
                  <small>{auth.user.role.toLowerCase()} workspace</small>
                </span>
              </button>
              <button className="icon-button light" aria-label="Sign out" title="Sign out" onClick={signOut}>
                <LogOut size={16} />
              </button>
            </div>
          </div>
        </aside>
        <div className="main-shell">
          <header className="topbar">
            <div className="breadcrumbs">
              <button
                className="icon-button mobile-only"
                aria-label="Open navigation"
                onClick={() => setSidebar(true)}
              >
                <Menu size={20} />
              </button>
              <span>Workspace</span>
              <ChevronRight size={13} />
              <strong>{title}</strong>
            </div>
            <div className="topbar-actions">
              <form
                className="global-search"
                onSubmit={(e) => {
                  e.preventDefault();
                  navigate('vehicles', search);
                }}
              >
                <Search size={16} />
                <input
                  aria-label="Search fleet"
                  placeholder="Search fleet..."
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </form>
              <span className="env-badge">DEMO</span>
              <ThemeToggle />
              <button
                className="icon-button notification"
                aria-label="View alerts"
                onClick={() => navigate('alerts')}
              >
                <Bell size={19} />
                {!!overview.data?.criticalAlerts && <i />}
              </button>
              <button
                className="avatar topbar-avatar"
                aria-label="Open user profile"
                title={`${auth.user.name} - User profile`}
                aria-haspopup="dialog"
                aria-expanded={profile}
                onClick={openProfile}
              >
                {initials(auth.user.name)}
              </button>
            </div>
          </header>
          <main className="main-content">
            {overview.isError && (
              <div className="connection-banner">
                <AlertTriangle size={17} />
                Connection interrupted. The last available data may be stale.
                <button onClick={() => overview.refetch()}>Retry</button>
              </div>
            )}
            {!overview.data ? (
              <Loading />
            ) : locationState.view === 'overview' ? (
              <OverviewPage
                data={overview.data}
                onVehicle={setVehicle}
                simulation={simulation.data}
                toggleSimulation={toggleSimulation}
              />
            ) : locationState.view === 'vehicles' ? (
              <VehiclesPage search={locationState.target} onVehicle={setVehicle} />
            ) : locationState.view === 'alerts' ? (
              <AlertsPage onVehicle={setVehicle} />
            ) : locationState.view === 'integrations' ? (
              <IntegrationsPage />
            ) : locationState.view === 'mappings' ? (
              <MappingsPage target={locationState.target} />
            ) : locationState.view === 'recovery' ? (
              <RecoveryPage />
            ) : locationState.view === 'analytics' ? (
              <AnalyticsPage />
            ) : (
              <AuditPage />
            )}
            {locationState.view === 'integrations' && <DriftPanel />}
            {locationState.view === 'audit' && <PrivacyPanel />}
            <footer className="page-footer">
              <span>
                <ShieldCheck size={13} />
                Tenant-isolated workspace
              </span>
              <span>
                FleetTruth <span className="footer-divider">/</span> Synthetic vehicle data
              </span>
            </footer>
          </main>
        </div>
        {vehicle && <VehicleDrawer vin={vehicle} onClose={() => setVehicle('')} />}
        {assistant && <Assistant onClose={() => setAssistant(false)} />}
        {profile && (
          <UserProfile
            user={auth.user}
            onClose={() => setProfile(false)}
            onSignOut={signOut}
            onAudit={() => {
              setProfile(false);
              navigate('audit');
            }}
          />
        )}
        {toast && (
          <div role="status" className={`toast ${toast.error ? 'error' : ''}`}>
            {toast.error ? <AlertTriangle size={17} /> : <Check size={17} />}
            <span>{toast.text}</span>
            <button className="icon-button" aria-label="Dismiss notification" onClick={() => setToast(null)}>
              <X size={15} />
            </button>
          </div>
        )}
      </div>
    </WorkspaceContext.Provider>
  );
}
function Login({ onLogin }: { onLogin: (data: { token: string; user: User }) => void }) {
  const [email, setEmail] = useState('engineer@fleettruth.demo');
  const [password, setPassword] = useState('FleetTruth2026!');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      onLogin(await post('/auth/login', { email, password }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <header>
        <Brand />
        <span className="env-badge">SYNTHETIC DEMO</span>
      </header>
      <main className="login-main">
        <div className="login-mark">
          <ShieldCheck size={28} />
        </div>
        <span className="eyebrow">CONNECTED VEHICLE INTELLIGENCE</span>
        <h1>Welcome to FleetTruth.</h1>
        <p>Meridian Logistics operations workspace</p>
        <form onSubmit={submit}>
          <label>
            Email address
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
          <button className="button primary login-submit" disabled={busy}>
            {busy ? (
              <Loader2 className="spin" size={18} />
            ) : (
              <>
                Open workspace
                <ArrowRight size={17} />
              </>
            )}
          </button>
        </form>
        <div className="demo-accounts">
          <span>Demo access</span>
          <button onClick={() => setEmail('engineer@fleettruth.demo')}>Engineer</button>
          <button onClick={() => setEmail('viewer@fleettruth.demo')}>Reviewer</button>
        </div>
        <div className="login-facts">
          <span>
            <Truck size={16} />
            100,000 vehicles
          </span>
          <span>
            <Workflow size={16} />4 OEM streams
          </span>
          <span>
            <ShieldCheck size={16} />
            Audited decisions
          </span>
        </div>
      </main>
      <footer>
        FleetTruth <span>Independent connected vehicle intelligence project</span>
      </footer>
    </div>
  );
}
function OverviewPage({
  data,
  onVehicle,
  simulation,
  toggleSimulation,
}: {
  data: Overview;
  onVehicle: (vin: string) => void;
  simulation?: Simulation;
  toggleSimulation: () => void;
}) {
  const { navigate, notify, canEdit } = useWorkspace();
  const audit = useQuery<Audit[]>({
    queryKey: ['audit'],
    queryFn: () => api('/audit'),
    refetchInterval: 5000,
  });
  const incident = data.incidents.find((i) => i.status === 'OPEN');
  return (
    <>
      <PageHeading
        eyebrow="FLEET OPERATIONS"
        title="Fleet overview"
        description="Meridian Logistics / All fleets"
      >
        <button
          className="button secondary"
          onClick={() => {
            download('fleettruth-report.json', data);
            notify('Fleet report exported');
          }}
        >
          <Download size={15} />
          Export report
        </button>
        <button className="button primary" onClick={() => navigate('vehicles')}>
          View fleet
          <ArrowUpRight size={16} />
        </button>
      </PageHeading>
      <div className="overview-toolbar">
        <div className="live-label">
          <span className={simulation?.running ? 'live-dot' : 'paused-dot'} />
          {simulation?.running ? 'Live telemetry' : 'Telemetry paused'}
          <span className="toolbar-divider" />
          <span className="subtle">Updated {relative(data.timestamp)}</span>
        </div>
        <button className="button tiny ghost" onClick={toggleSimulation} disabled={!canEdit}>
          {simulation?.running ? <Pause size={13} /> : <Play size={13} />}{' '}
          {simulation?.running ? 'Pause stream' : 'Resume stream'}
        </button>
      </div>
      <section className="metrics-band" aria-label="Fleet statistics">
        <div className="metric">
          <div className="metric-label">
            Enrolled vehicles
            <Truck size={17} />
          </div>
          <div className="metric-value">{number(data.vehicles)}</div>
          <div className="metric-foot">
            <span className="metric-chip green">
              <Radio size={11} />
              {number(data.reporting)} reporting
            </span>
            <span>last 5 min</span>
          </div>
        </div>
        <div className="metric">
          <div className="metric-label">
            Normalized events
            <Database size={17} />
          </div>
          <div className="metric-value">{number(data.accepted)}</div>
          <div className="metric-foot">
            <span className="metric-chip green">
              <Zap size={11} />
              {data.eventsPerSecond}/sec
            </span>
            <span>observed intake</span>
          </div>
        </div>
        <div className="metric">
          <div className="metric-label">
            Signal trust score
            <ShieldCheck size={17} />
          </div>
          <div className="metric-value">
            {data.quality.toFixed(1)}
            <small>%</small>
          </div>
          <div className="metric-foot">
            <span className={`metric-chip ${data.quarantined ? 'amber' : 'green'}`}>
              {number(data.quarantined)} quarantined
            </span>
          </div>
        </div>
        <div className="metric">
          <div className="metric-label">
            Critical alerts
            <Bell size={17} />
          </div>
          <div className={`metric-value ${data.criticalAlerts ? 'text-coral' : ''}`}>
            {data.criticalAlerts.toString().padStart(2, '0')}
          </div>
          <div className="metric-foot">
            <span className="metric-chip red">Requires attention</span>
            <span>{data.openAlerts} total open</span>
          </div>
        </div>
      </section>
      {incident ? (
        <div className="incident-banner">
          <span className="incident-icon">
            <AlertTriangle size={19} />
          </span>
          <div>
            <strong>{incident.oemName}: schema change detected</strong>
            <p>
              Version {incident.schemaVersion} has unverified signals. {number(data.quarantined)} events are
              awaiting a validated mapping.
            </p>
          </div>
          <button className="text-button amber-text" onClick={() => navigate('mappings')}>
            Review change
            <ArrowRight size={15} />
          </button>
        </div>
      ) : (
        <div className="healthy-banner">
          <ShieldCheck size={19} />
          <strong>All OEM contracts are healthy</strong>
          <span>No unresolved schema incidents</span>
        </div>
      )}
      <div className="overview-grid">
        <section className="workspace-panel chart-panel">
          <SectionTitle title="Signal throughput" detail="Events received per minute">
            <div className="chart-legends">
              <span>
                <i className="legend-dot green-bg" />
                Accepted
              </span>
              <span>
                <i className="legend-dot amber-bg" />
                Quarantined
              </span>
            </div>
          </SectionTitle>
          <div className="chart-summary">
            <strong>{number(data.processed)}</strong>
            <span>total received</span>
            <span className="period-pill">Last 60 minutes</span>
          </div>
          <SignalChart data={data.series} />
          <div className="chart-caption">
            <span>
              <span className="live-dot" />
              Measured from the synthetic stream
            </span>
            <span>{number(data.duplicates)} duplicate deliveries handled</span>
          </div>
        </section>
        <section className="workspace-panel oem-panel">
          <SectionTitle title="OEM connections" detail="4 connected sources">
            <span className="round-count">4</span>
          </SectionTitle>
          <div className="oem-list">
            {data.oems.map((o) => (
              <button key={o.id} className="oem-row" onClick={() => navigate('integrations', o.id)}>
                <span
                  className="oem-monogram"
                  style={{ background: o.color + '13', color: 'var(--text-primary)' }}
                >
                  {o.name
                    .split(' ')
                    .map((w) => w[0])
                    .join('')}
                </span>
                <span className="oem-row-main">
                  <strong>{o.name}</strong>
                  <small>{number(o.vehicles)} vehicles</small>
                </span>
                <span className="oem-row-status">
                  <span className={o.status === 'HEALTHY' ? 'status-dot green-bg' : 'status-dot amber-bg'} />
                  <strong>{o.quality.toFixed(1)}%</strong>
                  <small>{o.status === 'HEALTHY' ? 'Healthy' : 'Needs review'}</small>
                </span>
              </button>
            ))}
          </div>
          <div className="oem-panel-footer">
            <span>
              <span className="status-dot green-bg" />
              {data.oems.filter((o) => o.status === 'HEALTHY').length} healthy
            </span>
            <LinkButton onClick={() => navigate('integrations')}>Manage sources</LinkButton>
          </div>
        </section>
      </div>
      <div className="overview-bottom">
        <section className="workspace-panel">
          <SectionTitle title="Priority alerts" detail="Vehicle issues that need your attention">
            <LinkButton onClick={() => navigate('alerts')}>View all alerts</LinkButton>
          </SectionTitle>
          <div className="table-scroll">
            <table className="data-table compact-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Alert</th>
                  <th>Severity</th>
                  <th>Reported</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.alerts.slice(0, 5).map((alert) => (
                  <tr key={alert.id}>
                    <td>
                      <button className="vehicle-link" onClick={() => onVehicle(alert.vin)}>
                        <span className="vehicle-icon">
                          <Truck size={16} />
                        </span>
                        <span>
                          <strong>{alert.registration}</strong>
                          <small>{alert.oemName}</small>
                        </span>
                      </button>
                    </td>
                    <td>
                      <strong className="cell-title">{alert.title}</strong>
                      <small className="cell-subtitle">
                        {alert.status === 'ACKNOWLEDGED' ? 'Assigned for review' : 'Awaiting review'}
                      </small>
                    </td>
                    <td>
                      <Badge status={alert.severity} />
                    </td>
                    <td className="muted">{relative(alert.updatedAt)}</td>
                    <td>
                      <button
                        className="icon-button"
                        title="Open alert"
                        aria-label={`Open ${alert.registration}`}
                        onClick={() => navigate('alerts', alert.id)}
                      >
                        <ArrowUpRight size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {data.alerts.length === 0 && <Empty title="No active vehicle alerts" />}
          </div>
        </section>
        <section className="workspace-panel activity-panel">
          <SectionTitle title="Recent activity">
            <button
              className="icon-button"
              title="View audit trail"
              aria-label="View audit trail"
              onClick={() => navigate('audit')}
            >
              <MoreHorizontal size={18} />
            </button>
          </SectionTitle>
          <div className="activity-list">
            {audit.data?.slice(0, 4).map((event, i) => (
              <div className="activity-item" key={event.id}>
                <span className={`activity-node node-${i % 3}`}>
                  {event.action.startsWith('AGENT') ? (
                    <Sparkles size={13} />
                  ) : event.action.includes('REPLAY') ? (
                    <RefreshCw size={13} />
                  ) : (
                    <GitBranch size={13} />
                  )}
                </span>
                <div>
                  <strong>{event.action.toLowerCase().replaceAll('_', ' ')}</strong>
                  <p>{event.detail}</p>
                  <small>{relative(event.createdAt)}</small>
                </div>
              </div>
            ))}
          </div>
          <LinkButton onClick={() => navigate('audit')}>Open audit trail</LinkButton>
        </section>
      </div>
    </>
  );
}
function Assistant({ onClose }: { onClose: () => void }) {
  useModalFocus('Fleet assistant', onClose);
  const { navigate, notify } = useWorkspace();
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<{ question: string; reply: AssistantReply }[]>([]);
  const [busy, setBusy] = useState(false);
  async function ask(text: string) {
    if (!text.trim() || busy) return;
    setQuestion('');
    setBusy(true);
    try {
      const reply = await post<AssistantReply>('/assistant', { question: text });
      setMessages((m) => [...m, { question: text, reply }]);
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="drawer-overlay" onClick={onClose}>
      <aside
        className="assistant-drawer"
        role="dialog"
        aria-label="Fleet assistant"
        aria-modal="true"
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <span className="assistant-icon">
            <Sparkles size={19} />
          </span>
          <div>
            <h2>Fleet assistant</h2>
            <span>
              <span className="live-dot" />
              Grounded in your workspace
            </span>
          </div>
          <button className="icon-button" onClick={onClose} aria-label="Close assistant">
            <X size={19} />
          </button>
        </header>
        <div className="assistant-messages">
          {messages.length === 0 ? (
            <div className="assistant-welcome">
              <Sparkles size={28} />
              <h2>What needs your attention?</h2>
              <p>Meridian Logistics / Current fleet evidence</p>
              {[
                'What needs attention across the fleet?',
                'Explain the Helix schema change',
                'Which events should I replay?',
              ].map((q) => (
                <button key={q} onClick={() => ask(q)}>
                  {q}
                  <ArrowUpRight size={15} />
                </button>
              ))}
            </div>
          ) : (
            messages.map((m, i) => (
              <div className="conversation" key={i}>
                <div className="user-message">{m.question}</div>
                <div className="agent-message">
                  <span className="agent-label">
                    <Sparkles size={13} />
                    FLEET ASSISTANT
                  </span>
                  <p>{m.reply.answer}</p>
                  {m.reply.tools.length > 0 && (
                    <details>
                      <summary>
                        <ShieldCheck size={13} />
                        {m.reply.tools.length} audited tool calls
                      </summary>
                      {m.reply.tools.map((t, j) => (
                        <div className="tool-result" key={j}>
                          <Check size={12} />
                          <span>
                            <strong>{t.name.replaceAll('_', ' ')}</strong>
                            <small>{t.result}</small>
                          </span>
                        </div>
                      ))}
                    </details>
                  )}
                  {m.reply.action && (
                    <button
                      className="button secondary small"
                      onClick={() => {
                        navigate(
                          m.reply.action === 'mapping' ? 'mappings' : (m.reply.action as View),
                          m.reply.target,
                        );
                        onClose();
                      }}
                    >
                      Open {m.reply.action === 'mapping' ? 'mapping studio' : m.reply.action}
                      <ArrowRight size={14} />
                    </button>
                  )}
                </div>
              </div>
            ))
          )}
          {busy && (
            <div className="agent-loading">
              <Loader2 size={16} className="spin" />
              Inspecting fleet evidence...
            </div>
          )}
        </div>
        <form
          className="assistant-composer"
          onSubmit={(e) => {
            e.preventDefault();
            ask(question);
          }}
        >
          <textarea
            aria-label="Ask fleet assistant"
            placeholder="Ask about your fleet..."
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            maxLength={2000}
          />
          <button
            className="button primary icon-only"
            disabled={busy || !question.trim()}
            aria-label="Send question"
          >
            <ArrowUp size={19} />
          </button>
        </form>
        <footer>
          <ShieldCheck size={12} />
          Read-only recommendations. Actions require approval.
        </footer>
      </aside>
    </div>
  );
}
