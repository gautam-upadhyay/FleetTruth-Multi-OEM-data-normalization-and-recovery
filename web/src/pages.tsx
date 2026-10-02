import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  ArrowRight,
  ArrowUpRight,
  BatteryCharging,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Code2,
  Download,
  GitBranch,
  Loader2,
  Play,
  RefreshCw,
  Search,
  ShieldCheck,
  Truck,
  X,
  Zap,
} from 'lucide-react';
import { api, download, post } from './api';
import {
  Badge,
  dateTime,
  Dialog,
  Empty,
  Loading,
  number,
  PageHeading,
  relative,
  SectionTitle,
  SignalChart,
  Success,
} from './components';
import { useWorkspace } from './context';
import type { Alert, Audit, Mapping, Oem, Overview, Point, Preview, Replay, Rule, Vehicle } from './types';

import { useModalFocus } from './useModalFocus';

export function VehiclesPage({
  search: initialSearch,
  onVehicle,
}: {
  search: string;
  onVehicle: (vin: string) => void;
}) {
  const [search, setSearch] = useState(initialSearch),
    [term, setTerm] = useState(initialSearch),
    [oem, setOem] = useState(''),
    [cursor, setCursor] = useState(''),
    [history, setHistory] = useState<string[]>([]);
  useEffect(() => {
    setSearch(initialSearch);
    setTerm(initialSearch);
    setCursor('');
    setHistory([]);
  }, [initialSearch]);
  useEffect(() => {
    if (search === term) return;
    const timer = setTimeout(() => {
      setTerm(search);
      setCursor('');
      setHistory([]);
    }, 250);
    return () => clearTimeout(timer);
  }, [search, term]);
  const query = useQuery<{ items: Vehicle[]; total: number; nextCursor: string }>({
    queryKey: ['vehicles', term, oem, cursor],
    queryFn: () => api(`/vehicles?search=${encodeURIComponent(term)}&oem=${oem}&cursor=${cursor}`),
    refetchInterval: 5000,
  });
  const oems = useQuery<Oem[]>({ queryKey: ['oems'], queryFn: () => api('/oems') });
  return (
    <>
      <PageHeading
        eyebrow="FLEET OPERATIONS"
        title="Vehicles"
        description="Enrolled vehicles across your connected OEMs"
      >
        <span className="count-label">
          <Truck size={15} />
          {number(query.data?.total)} vehicles
        </span>
      </PageHeading>
      <div className="table-toolbar">
        <label className="search-input">
          <Search size={16} />
          <input
            aria-label="Search vehicles"
            placeholder="Search registration, VIN, or driver"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <select
          aria-label="Filter by OEM"
          value={oem}
          onChange={(e) => {
            setOem(e.target.value);
            setCursor('');
            setHistory([]);
          }}
        >
          <option value="">All OEMs</option>
          {oems.data?.map((o) => (
            <option key={o.id} value={o.id}>
              {o.name}
            </option>
          ))}
        </select>
        <span className="toolbar-right subtle">
          {query.isFetching ? <Loader2 className="spin" size={14} /> : <span className="live-dot" />}Live
          vehicle state
        </span>
      </div>
      <section className="workspace-panel">
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Vehicle / VIN</th>
                <th>OEM & model</th>
                <th>Fleet / driver</th>
                <th>Battery</th>
                <th>Speed</th>
                <th>Signal quality</th>
                <th>Last reported</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {query.data?.items.map((v) => (
                <tr key={v.vin}>
                  <td>
                    <button className="vehicle-link" onClick={() => onVehicle(v.vin)}>
                      <span className="vehicle-icon">
                        <Truck size={17} />
                      </span>
                      <span>
                        <strong>{v.registration}</strong>
                        <small className="mono">{v.vin}</small>
                      </span>
                    </button>
                  </td>
                  <td>
                    <strong className="cell-title">{v.oemName}</strong>
                    <small className="cell-subtitle">{v.model}</small>
                  </td>
                  <td>
                    <strong className="cell-title">{v.fleetName}</strong>
                    <small className="cell-subtitle">{v.driverName}</small>
                  </td>
                  <td>
                    {v.socPct == null ? (
                      <span className="muted">--</span>
                    ) : (
                      <span className={`battery-value ${v.socPct < 15 ? 'low' : ''}`}>
                        <BatteryCharging size={16} />
                        {v.socPct.toFixed(0)}%
                      </span>
                    )}
                  </td>
                  <td>{v.speedKmh == null ? '--' : `${v.speedKmh.toFixed(0)} km/h`}</td>
                  <td>
                    <Badge status={v.quality || 'UNKNOWN'} />
                  </td>
                  <td className="muted">{relative(v.eventTime)}</td>
                  <td>
                    <button
                      className="icon-button"
                      aria-label={`View ${v.registration}`}
                      onClick={() => onVehicle(v.vin)}
                    >
                      <ChevronRight size={16} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {query.isPending ? (
            <Loading />
          ) : query.isError ? (
            <Empty title="Vehicle data unavailable" detail={query.error.message} />
          ) : !query.data.items.length ? (
            <Empty title="No matching vehicles" detail="Try a different registration, VIN, or OEM." />
          ) : null}
        </div>
        <div className="table-footer">
          <span>
            {number(query.data?.total)} results <span className="footer-divider">/</span> Page{' '}
            {history.length + 1}
          </span>
          <div>
            <button
              className="button secondary small"
              disabled={!history.length}
              onClick={() => {
                setCursor(history[history.length - 1]);
                setHistory((h) => h.slice(0, -1));
              }}
            >
              <ChevronLeft size={14} />
              Previous
            </button>
            <button
              className="button secondary small"
              disabled={!query.data?.nextCursor}
              onClick={() => {
                setHistory((h) => [...h, cursor]);
                setCursor(query.data!.nextCursor);
              }}
            >
              Next
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      </section>
    </>
  );
}
export function VehicleDrawer({ vin, onClose }: { vin: string; onClose: () => void }) {
  useModalFocus('Vehicle details', onClose);
  const query = useQuery<Vehicle>({
    queryKey: ['vehicle', vin],
    queryFn: () => api('/vehicles/' + vin),
    refetchInterval: 5000,
  });
  const [eventId, setEventId] = useState('');
  const { canEdit } = useWorkspace();
  const v = query.data;
  const event = v?.events?.find((e) => e.id === eventId);
  return (
    <div className="drawer-overlay" onClick={onClose}>
      <aside
        className="detail-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Vehicle details"
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <span className="eyebrow">VEHICLE DETAIL</span>
          <button className="icon-button" aria-label="Close vehicle" onClick={onClose}>
            <X size={20} />
          </button>
        </header>
        {query.isPending ? (
          <Loading />
        ) : query.isError ? (
          <Empty title="Vehicle unavailable" detail={query.error.message} />
        ) : (
          v && (
            <div className="drawer-content">
              <div className="vehicle-detail-title">
                <span className="large-vehicle-icon">
                  <Truck size={32} />
                </span>
                <div>
                  <h2>{v.registration}</h2>
                  <p>
                    {v.oemName} / {v.model}
                  </p>
                </div>
                <Badge status={v.quality || 'UNKNOWN'} />
              </div>
              <div className="vin-line mono">{v.vin}</div>
              <div className="detail-metrics">
                <div>
                  <span>Battery</span>
                  <strong>{v.socPct == null ? '--' : v.socPct.toFixed(1) + '%'}</strong>
                </div>
                <div>
                  <span>Speed</span>
                  <strong>
                    {v.speedKmh == null ? '--' : v.speedKmh.toFixed(1)}
                    <small> km/h</small>
                  </strong>
                </div>
                <div>
                  <span>Odometer</span>
                  <strong>{v.odometerKm == null ? '--' : number(Math.round(v.odometerKm))}</strong>
                </div>
              </div>
              <dl className="definition-list">
                <div>
                  <dt>Assigned driver</dt>
                  <dd>{v.driverName}</dd>
                </div>
                <div>
                  <dt>Fleet</dt>
                  <dd>{v.fleetName}</dd>
                </div>
                <div>
                  <dt>Last reported</dt>
                  <dd>{relative(v.eventTime)}</dd>
                </div>
                <div>
                  <dt>Location {canEdit ? '' : '(masked)'}</dt>
                  <dd>
                    {v.latitude == null
                      ? 'Not available'
                      : `${v.latitude.toFixed(canEdit ? 4 : 1)}, ${v.longitude?.toFixed(canEdit ? 4 : 1)}`}
                  </dd>
                </div>
              </dl>
              <SectionTitle title="Vehicle alerts" />
              {v.alerts?.length ? (
                v.alerts.map((a) => (
                  <div className="vehicle-alert" key={a.id}>
                    <Badge status={a.severity} />
                    <strong>{a.title}</strong>
                    <p>{a.description}</p>
                    <small>
                      {relative(a.createdAt)} / {a.status.toLowerCase()}
                    </small>
                  </div>
                ))
              ) : (
                <p className="subtle">No alerts recorded.</p>
              )}
              {canEdit && (
                <>
                  <SectionTitle title="Event timeline" detail="Latest 40 events" />
                  {v.events?.map((e) => (
                    <button
                      className={`event-row ${eventId === e.id ? 'selected' : ''}`}
                      key={e.id}
                      onClick={() => setEventId(eventId === e.id ? '' : e.id)}
                    >
                      <span
                        className="status-dot"
                        style={{ background: e.status === 'ACCEPTED' ? '#24876a' : '#d99a3e' }}
                      />
                      <span>
                        <strong>
                          {e.status === 'ACCEPTED' ? 'Telemetry normalized' : 'Event quarantined'}
                        </strong>
                        <small>{dateTime(e.eventTime)}</small>
                      </span>
                      <Code2 size={15} />
                    </button>
                  ))}
                  {event && (
                    <div className="event-evidence">
                      <h3>Original event</h3>
                      <pre>{JSON.stringify(JSON.parse(event.payload), null, 2)}</pre>
                      <h3>Normalized event</h3>
                      <pre>
                        {event.normalized
                          ? JSON.stringify(JSON.parse(event.normalized), null, 2)
                          : event.reason}
                      </pre>
                    </div>
                  )}
                </>
              )}
            </div>
          )
        )}
      </aside>
    </div>
  );
}
export function AlertsPage({ onVehicle }: { onVehicle: (vin: string) => void }) {
  const [status, setStatus] = useState('ALL');
  const [busy, setBusy] = useState('');
  const { notify, canEdit } = useWorkspace();
  const client = useQueryClient();
  const query = useQuery<Alert[]>({
    queryKey: ['alerts', status],
    queryFn: () => api('/alerts?status=' + status),
    refetchInterval: 3000,
  });
  async function update(id: string, state: string) {
    setBusy(id);
    try {
      await api('/alerts/' + id, { method: 'PATCH', body: JSON.stringify({ status: state }) });
      client.invalidateQueries();
      notify(state === 'RESOLVED' ? 'Alert resolved' : 'Alert acknowledged');
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy('');
    }
  }
  return (
    <>
      <PageHeading
        eyebrow="FLEET OPERATIONS"
        title="Alert center"
        description="Prioritized vehicle issues, backed by traceable telemetry"
      >
        <button className="button secondary" onClick={() => download('fleettruth-alerts.json', query.data)}>
          <Download size={15} />
          Export alerts
        </button>
      </PageHeading>
      <div className="table-toolbar">
        <div className="tabs">
          {['ALL', 'OPEN', 'ACKNOWLEDGED', 'RESOLVED'].map((s) => (
            <button key={s} className={status === s ? 'active' : ''} onClick={() => setStatus(s)}>
              {s === 'ALL' ? 'All alerts' : s[0] + s.substring(1).toLowerCase()}
            </button>
          ))}
        </div>
        <span className="subtle">{query.data?.length || 0} alerts</span>
      </div>
      <section className="workspace-panel">
        <div className="table-scroll">
          <table className="data-table alert-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Vehicle</th>
                <th>Alert & evidence</th>
                <th>Status</th>
                <th>Updated</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {query.data?.map((a) => (
                <tr key={a.id}>
                  <td>
                    <Badge status={a.severity} />
                  </td>
                  <td>
                    <button className="vehicle-link" onClick={() => onVehicle(a.vin)}>
                      <span>
                        <strong>{a.registration}</strong>
                        <small>{a.oemName}</small>
                      </span>
                    </button>
                  </td>
                  <td>
                    <strong className="cell-title">{a.title}</strong>
                    <p className="alert-description">{a.description}</p>
                  </td>
                  <td>
                    <Badge status={a.status} />
                  </td>
                  <td className="muted nowrap">{relative(a.updatedAt)}</td>
                  <td>
                    {a.status === 'RESOLVED' ? (
                      <span className="resolved-icon">
                        <CheckCheck size={18} />
                      </span>
                    ) : (
                      <button
                        className="button secondary small"
                        disabled={!canEdit || busy === a.id}
                        onClick={() => update(a.id, a.status === 'OPEN' ? 'ACKNOWLEDGED' : 'RESOLVED')}
                      >
                        {busy === a.id ? <Loader2 className="spin" size={14} /> : <Check size={14} />}{' '}
                        {a.status === 'OPEN' ? 'Acknowledge' : 'Resolve'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {query.isPending ? (
            <Loading />
          ) : query.isError ? (
            <Empty title="Alerts unavailable" detail={query.error.message} />
          ) : !query.data.length ? (
            <Empty title="No alerts in this view" />
          ) : null}
        </div>
      </section>
    </>
  );
}
export function IntegrationsPage() {
  const { navigate, notify, canEdit } = useWorkspace();
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const query = useQuery<Oem[]>({ queryKey: ['oems'], queryFn: () => api('/oems'), refetchInterval: 3000 });
  const overview = useQuery<Overview>({ queryKey: ['overview'], queryFn: () => api('/overview') });
  async function drift() {
    setBusy(true);
    try {
      const result = await post<{ mappingId: string }>('/simulation/drift');
      client.invalidateQueries();
      notify('Helix schema change injected');
      navigate('mappings', result.mappingId);
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeading
        eyebrow="DATA & INTELLIGENCE"
        title="OEM integrations"
        description="Connection health, signal quality, and schema contracts"
      >
        <button
          className="button secondary"
          onClick={drift}
          disabled={!canEdit || busy || !!overview.data?.openIncidents}
        >
          {busy ? <Loader2 className="spin" size={15} /> : <Zap size={15} />}Inject schema change
        </button>
      </PageHeading>
      <div className="summary-strip">
        <div>
          <strong>04</strong>
          <span>Connected OEMs</span>
        </div>
        <div>
          <strong>
            {query.data
              ?.filter((o) => o.status === 'HEALTHY')
              .length.toString()
              .padStart(2, '0') || '00'}
          </strong>
          <span>Healthy contracts</span>
        </div>
        <div>
          <strong className="amber-text">
            {overview.data?.openIncidents.toString().padStart(2, '0') || '00'}
          </strong>
          <span>Open incidents</span>
        </div>
        <div>
          <strong>{number(overview.data?.quarantined)}</strong>
          <span>Quarantined events</span>
        </div>
      </div>
      <section className="workspace-panel">
        <SectionTitle title="Connected sources" detail="Synthetic OEM feeds / Approved schemas only" />
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>OEM source</th>
                <th>Vehicles</th>
                <th>Received events</th>
                <th>Signal trust</th>
                <th>Quarantine</th>
                <th>Contract status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {query.data?.map((o) => (
                <tr key={o.id}>
                  <td>
                    <div className="source-name">
                      <span
                        className="oem-monogram"
                        style={{ background: o.color + '16', color: 'var(--text-primary)' }}
                      >
                        {o.name
                          .split(' ')
                          .map((w) => w[0])
                          .join('')}
                      </span>
                      <span>
                        <strong>{o.name}</strong>
                        <small>Last event {relative(o.lastSeen)}</small>
                      </span>
                    </div>
                  </td>
                  <td>{number(o.vehicles)}</td>
                  <td>{number(o.events)}</td>
                  <td>
                    <div className="quality-cell">
                      <strong>{o.quality.toFixed(1)}%</strong>
                      <span className="quality-track">
                        <i
                          style={{
                            width: o.quality + '%',
                            background: o.status === 'HEALTHY' ? '#24876a' : '#d99a3e',
                          }}
                        />
                      </span>
                    </div>
                  </td>
                  <td className={o.quarantined ? 'amber-text' : ''}>{number(o.quarantined)}</td>
                  <td>
                    <Badge status={o.status} />
                  </td>
                  <td>
                    <button className="button secondary small" onClick={() => navigate('mappings', o.id)}>
                      <GitBranch size={14} />
                      Mappings
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="incident-section">
        <SectionTitle title="Schema incidents" detail="Changes detected in source contracts" />
        {overview.data?.incidents.length ? (
          overview.data.incidents.map((i) => (
            <div className="incident-detail" key={i.id}>
              <span className={`incident-icon ${i.status === 'RESOLVED' ? 'resolved' : ''}`}>
                {i.status === 'RESOLVED' ? <ShieldCheck size={21} /> : <AlertTriangle size={21} />}
              </span>
              <div>
                <h3>
                  {i.oemName} / schema {i.schemaVersion}
                </h3>
                <p>{i.description}</p>
                <small>Detected {dateTime(i.createdAt)}</small>
              </div>
              <Badge status={i.status} />
              <button
                className="icon-button"
                aria-label="Inspect incident mapping"
                onClick={() => navigate('mappings', i.oemId)}
              >
                <ArrowUpRight size={18} />
              </button>
            </div>
          ))
        ) : (
          <Empty title="No schema incidents" />
        )}
      </section>
    </>
  );
}
export function MappingsPage({ target }: { target: string }) {
  const { notify, canEdit, navigate } = useWorkspace();
  const client = useQueryClient();
  const [selected, setSelected] = useState('');
  const [rules, setRules] = useState<Rule[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [tab, setTab] = useState<'rules' | 'payload'>('rules');
  const query = useQuery<Mapping[]>({ queryKey: ['mappings'], queryFn: () => api('/mappings') });
  const mapping = query.data?.find((m) => m.id === selected);
  useEffect(() => {
    if (query.data?.length) {
      const wanted =
        query.data.find((m) => m.id === target) ||
        query.data.find((m) => m.oemId === target && m.status === 'DRAFT') ||
        query.data.find((m) => m.oemId === target);
      if (wanted) setSelected(wanted.id);
      else if (!selected) setSelected(query.data[0].id);
    }
  }, [query.data, target, selected]);
  useEffect(() => {
    if (mapping) {
      setRules(mapping.rules.map((r) => ({ ...r })));
      setPreview(null);
      if (canEdit)
        post<Preview>(`/mappings/${mapping.id}/preview`)
          .then(setPreview)
          .catch(() => {});
    }
  }, [mapping, canEdit]);
  async function validate() {
    if (!mapping) return;
    setBusy('validate');
    try {
      const p = await post<Preview>(`/mappings/${mapping.id}/preview`, { rules });
      setPreview(p);
      notify(`${p.valid} of ${p.total} samples passed`, p.invalid > 0);
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy('');
    }
  }
  async function approve() {
    if (!mapping) return;
    setBusy('approve');
    try {
      await post(`/mappings/${mapping.id}/approve`, { rules });
      setConfirm(false);
      client.invalidateQueries();
      notify('Mapping approved. Quarantined events are ready for replay.');
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy('');
    }
  }
  async function replay() {
    if (!mapping) return;
    setBusy('replay');
    try {
      await post('/replays', { oem: mapping.oemId });
      client.invalidateQueries();
      navigate('recovery');
      notify('Replay started');
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy('');
    }
  }
  function change(index: number, key: 'source' | 'scale' | 'offset', value: string) {
    setRules((current) =>
      current.map((rule, i) =>
        i === index ? { ...rule, [key]: key === 'source' ? value : Number(value) } : rule,
      ),
    );
    setPreview(null);
  }
  return (
    <>
      <PageHeading
        eyebrow="DATA & INTELLIGENCE"
        title="Mapping studio"
        description="Versioned signal contracts with traceable approval"
      >
        <span className="count-label">
          <ShieldCheck size={15} />
          Human approval required
        </span>
      </PageHeading>
      <div className="mapping-workspace">
        <aside className="mapping-sidebar">
          <span className="eyebrow">SCHEMA VERSIONS</span>
          {query.data?.map((m) => (
            <button
              key={m.id}
              className={`mapping-selector ${selected === m.id ? 'selected' : ''}`}
              onClick={() => {
                setSelected(m.id);
                location.hash = '/mappings';
              }}
            >
              <span className="source-dot" style={{ background: m.oemColor }} />
              <span>
                <strong>{m.oemName}</strong>
                <small>
                  Schema {m.schemaVersion} / revision {m.version}
                </small>
              </span>
              {m.status === 'DRAFT' ? <span className="draft-dot" /> : <Check size={13} />}
            </button>
          ))}
          {query.isPending && <Loading />}
        </aside>
        <section className="mapping-editor">
          {mapping ? (
            <>
              <div className="mapping-editor-header">
                <div>
                  <span className="eyebrow">{mapping.oemName}</span>
                  <h2>
                    Schema {mapping.schemaVersion}
                    <Badge status={mapping.status} />
                  </h2>
                  <p className="mono">
                    {mapping.id.substring(0, 8)} / revision {mapping.version}
                  </p>
                </div>
                <div className="mapping-header-actions">
                  <button className="button secondary small" onClick={validate} disabled={!canEdit || !!busy}>
                    {busy === 'validate' ? <Loader2 size={14} className="spin" /> : <Play size={14} />}Run
                    validation
                  </button>
                  {mapping.status === 'DRAFT' ? (
                    <button
                      className="button primary small"
                      disabled={!canEdit || !!busy || !preview || preview.invalid > 0 || preview.total === 0}
                      onClick={() => setConfirm(true)}
                    >
                      <Check size={14} />
                      Approve mapping
                    </button>
                  ) : (
                    <button className="button primary small" disabled={!canEdit || !!busy} onClick={replay}>
                      {busy === 'replay' ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
                      Replay events
                    </button>
                  )}
                </div>
              </div>
              <div className="mapping-tabs tabs">
                <button className={tab === 'rules' ? 'active' : ''} onClick={() => setTab('rules')}>
                  <GitBranch size={14} />
                  Signal mappings
                </button>
                <button className={tab === 'payload' ? 'active' : ''} onClick={() => setTab('payload')}>
                  <Code2 size={14} />
                  Payload comparison
                </button>
              </div>
              {mapping.status === 'DRAFT' && (
                <div className="mapping-notice">
                  <AlertTriangle size={16} />
                  <span>This schema is awaiting approval. Source events remain quarantined.</span>
                </div>
              )}
              {tab === 'rules' ? (
                <div className="table-scroll">
                  <table className="data-table mapping-table">
                    <thead>
                      <tr>
                        <th>Canonical signal</th>
                        <th>Source JSON pointer</th>
                        <th>Scale</th>
                        <th>Offset</th>
                        <th>Required</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rules.map((r, i) => (
                        <tr key={r.field}>
                          <td>
                            <code>{r.field}</code>
                          </td>
                          <td>
                            <input
                              aria-label={`${r.field} source`}
                              value={r.source}
                              disabled={!canEdit || mapping.status !== 'DRAFT'}
                              onChange={(e) => change(i, 'source', e.target.value)}
                            />
                          </td>
                          <td>
                            <input
                              aria-label={`${r.field} scale`}
                              type="number"
                              step="any"
                              value={r.scale}
                              disabled={!canEdit || mapping.status !== 'DRAFT'}
                              onChange={(e) => change(i, 'scale', e.target.value)}
                            />
                          </td>
                          <td>
                            <input
                              aria-label={`${r.field} offset`}
                              type="number"
                              step="any"
                              value={r.offset}
                              disabled={!canEdit || mapping.status !== 'DRAFT'}
                              onChange={(e) => change(i, 'offset', e.target.value)}
                            />
                          </td>
                          <td>
                            {r.required ? (
                              <Check size={15} className="green-text" />
                            ) : (
                              <span className="muted">Optional</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="payload-comparison">
                  <div>
                    <h3>
                      Source event <span>OEM payload</span>
                    </h3>
                    <pre>
                      {preview?.samples[0]
                        ? JSON.stringify(preview.samples[0].raw, null, 2)
                        : 'No sample available'}
                    </pre>
                  </div>
                  <div>
                    <h3>
                      Normalized event <span>FleetTruth contract</span>
                    </h3>
                    <pre>
                      {preview?.samples[0]
                        ? JSON.stringify(preview.samples[0].normalized, null, 2)
                        : 'Run validation to inspect output'}
                    </pre>
                  </div>
                </div>
              )}
              <div className="validation-result">
                <SectionTitle
                  title="Validation results"
                  detail={preview ? `${preview.total} recent source samples` : 'No current validation result'}
                >
                  {preview && (
                    <Badge status={preview.invalid ? 'FAILED' : 'VALID'}>
                      {preview.invalid ? 'Validation failed' : 'All checks passed'}
                    </Badge>
                  )}
                </SectionTitle>
                {preview ? (
                  <>
                    <div className="validation-metrics">
                      <div>
                        <CheckCheck size={18} />
                        <strong>{preview.valid}</strong>
                        <span>Passed</span>
                      </div>
                      <div>
                        <AlertTriangle size={18} />
                        <strong>{preview.invalid}</strong>
                        <span>Failed</span>
                      </div>
                      <div>
                        <GitBranch size={18} />
                        <strong>{rules.length}</strong>
                        <span>Signal mappings</span>
                      </div>
                    </div>
                    {preview.samples
                      .filter((s) => !s.valid)
                      .slice(0, 3)
                      .map((s) => (
                        <div className="form-error" key={s.eventId}>
                          {s.issues.join('; ')}
                        </div>
                      ))}
                    {preview.samples[0]?.valid && (
                      <div className="conversion-example">
                        <span>Battery conversion</span>
                        <code>{rules.find((r) => r.field === 'socPct')?.source}</code>
                        <ArrowRight size={16} />
                        <strong>{Number(preview.samples[0].normalized?.socPct).toFixed(1)}%</strong>
                        <span className="green-text">
                          <Check size={13} />
                          Range validated
                        </span>
                      </div>
                    )}
                  </>
                ) : (
                  <p className="subtle">
                    {canEdit
                      ? 'Validation is required after changing a mapping.'
                      : 'Mapping validation is available to integration engineers.'}
                  </p>
                )}
              </div>
              <div className="mapping-audit">
                <ShieldCheck size={14} />
                {mapping.approvedBy
                  ? `Approved by ${mapping.approvedBy} / ${relative(mapping.approvedAt)}`
                  : 'Draft mappings cannot affect live vehicle decisions.'}
              </div>
            </>
          ) : (
            <Empty title="Select a schema version" />
          )}
        </section>
      </div>
      {confirm && mapping && (
        <Dialog title="Approve this mapping?" onClose={() => setConfirm(false)}>
          <p className="dialog-description">
            Publish {mapping.oemName} schema {mapping.schemaVersion}, revision {mapping.version}. New matching
            events will use these conversion rules.
          </p>
          <Success>{preview?.valid} sample events passed validation.</Success>
          <p className="subtle">
            Existing quarantined events stay available for a separate replay. This approval is recorded in the
            audit trail.
          </p>
          <div className="dialog-actions">
            <button className="button secondary" onClick={() => setConfirm(false)}>
              Cancel
            </button>
            <button className="button primary" disabled={!!busy} onClick={approve}>
              {busy ? <Loader2 className="spin" size={15} /> : <Check size={15} />}Confirm approval
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}
export function RecoveryPage() {
  const { notify, canEdit } = useWorkspace();
  const client = useQueryClient();
  const [oem, setOem] = useState('helix'),
    [busy, setBusy] = useState(false);
  const query = useQuery<Replay[]>({
    queryKey: ['replays'],
    queryFn: () => api('/replays'),
    refetchInterval: 1500,
  });
  const overview = useQuery<Overview>({ queryKey: ['overview'], queryFn: () => api('/overview') });
  async function start() {
    setBusy(true);
    try {
      await post('/replays', { oem });
      client.invalidateQueries();
      notify('Recovery job started');
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  const recovered = query.data?.reduce((s, r) => s + r.recovered, 0) || 0;
  return (
    <>
      <PageHeading
        eyebrow="DATA & INTELLIGENCE"
        title="Recovery center"
        description="Reprocess retained events with approved mapping versions"
      >
        <select aria-label="Replay OEM" value={oem} onChange={(e) => setOem(e.target.value)}>
          {['helix', 'aster', 'nord', 'vertex'].map((o) => (
            <option value={o} key={o}>
              {o[0].toUpperCase() + o.substring(1)}
            </option>
          ))}
        </select>
        <button className="button primary" disabled={!canEdit || busy} onClick={start}>
          {busy ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}Start replay
        </button>
      </PageHeading>
      <div className="summary-strip">
        <div>
          <strong className="amber-text">{number(overview.data?.quarantined)}</strong>
          <span>Events in quarantine</span>
        </div>
        <div>
          <strong className="green-text">{number(recovered)}</strong>
          <span>Events recovered</span>
        </div>
        <div>
          <strong>{query.data?.filter((j) => j.status === 'COMPLETED').length || 0}</strong>
          <span>Completed jobs</span>
        </div>
        <div>
          <strong>{query.data?.filter((j) => j.status === 'RUNNING').length || 0}</strong>
          <span>Running now</span>
        </div>
      </div>
      <section className="workspace-panel">
        <SectionTitle
          title="Replay history"
          detail="Original event timestamps and normalization history are preserved"
        />
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Replay job</th>
                <th>OEM</th>
                <th>Progress</th>
                <th>Recovered</th>
                <th>Remaining</th>
                <th>Status</th>
                <th>Started</th>
              </tr>
            </thead>
            <tbody>
              {query.data?.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong className="cell-title mono">{r.id.slice(0, 8)}</strong>
                    <small className="cell-subtitle">{r.requestedBy.split('@')[0]}</small>
                  </td>
                  <td className="capitalize">{r.oemId}</td>
                  <td>
                    <div className="quality-cell">
                      <strong>{r.total ? Math.round(((r.recovered + r.failed) / r.total) * 100) : 0}%</strong>
                      <span className="quality-track">
                        <i
                          style={{
                            width: `${r.total ? ((r.recovered + r.failed) / r.total) * 100 : 0}%`,
                            background: '#24876a',
                          }}
                        />
                      </span>
                    </div>
                  </td>
                  <td className="green-text">{number(r.recovered)}</td>
                  <td>{number(r.failed)}</td>
                  <td>
                    <Badge status={r.status} />
                  </td>
                  <td className="muted">{relative(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {query.isPending ? (
            <Loading />
          ) : !query.data?.length ? (
            <Empty
              title="No replay jobs yet"
              detail="Quarantined source events are retained in this workspace."
            />
          ) : null}
        </div>
      </section>
    </>
  );
}
export function AnalyticsPage() {
  const { notify } = useWorkspace();
  const query = useQuery<{
    series: Point[];
    oems: Oem[];
    replays: Replay[];
    daily: { day: string; total: number; accepted: number; quarantined: number }[];
  }>({ queryKey: ['analytics'], queryFn: () => api('/analytics'), refetchInterval: 10000 });
  return (
    <>
      <PageHeading
        eyebrow="INTELLIGENCE"
        title="Fleet analytics"
        description="Historical signal quality and recovery outcomes"
      >
        <button
          className="button secondary"
          disabled={!query.data}
          onClick={() => {
            download('fleettruth-analytics.json', query.data);
            notify('Analytics report exported');
          }}
        >
          <Download size={15} />
          Export report
        </button>
      </PageHeading>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <Empty title="Analytics unavailable" detail={query.error.message} />
      ) : (
        <>
          <div className="analytics-grid">
            <section className="workspace-panel">
              <SectionTitle title="Event history" detail="Measured events per minute / Last 60 minutes" />
              <SignalChart data={query.data.series} height={300} />
            </section>
            <section className="workspace-panel">
              <SectionTitle title="Signal quality by OEM" detail="Accepted events / All received events" />
              <div className="analytics-oems">
                {query.data.oems.map((o) => (
                  <div key={o.id}>
                    <div>
                      <span>{o.name}</span>
                      <strong>{o.quality.toFixed(2)}%</strong>
                    </div>
                    <span className="quality-track">
                      <i style={{ width: o.quality + '%', background: o.color }} />
                    </span>
                    <small>
                      {number(o.events - o.quarantined)} accepted / {number(o.events)} received
                    </small>
                  </div>
                ))}
              </div>
            </section>
          </div>
          <section className="workspace-panel">
            <SectionTitle
              title="Daily quality report"
              detail="Historical aggregates from the persisted event ledger"
            />
            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Received</th>
                    <th>Accepted</th>
                    <th>Quarantined</th>
                    <th>Acceptance rate</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.daily.map((d) => (
                    <tr key={d.day}>
                      <td>{d.day}</td>
                      <td>{number(d.total)}</td>
                      <td className="green-text">{number(d.accepted)}</td>
                      <td className="amber-text">{number(d.quarantined)}</td>
                      <td>{d.total ? ((d.accepted / d.total) * 100).toFixed(2) : '100'}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </>
  );
}
export function AuditPage() {
  const [reads, setReads] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const query = useQuery<Audit[]>({
    queryKey: ['audit', reads],
    queryFn: () => api('/audit?includeReads=' + reads),
    refetchInterval: 5000,
  });
  const items = query.data?.filter((e) => filter === 'ALL' || e.action.startsWith(filter));
  return (
    <>
      <PageHeading
        eyebrow="GOVERNANCE"
        title="Audit trail"
        description="Traceable data access, approvals, recovery, and assistant actions"
      >
        <button className="button secondary" onClick={() => download('fleettruth-audit.json', items)}>
          <Download size={15} />
          Export audit
        </button>
      </PageHeading>
      <div className="table-toolbar">
        <select aria-label="Filter audit actions" value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="ALL">All actions</option>
          <option value="MAPPING">Mapping decisions</option>
          <option value="REPLAY">Recovery jobs</option>
          <option value="AGENT">Assistant actions</option>
          <option value="ALERT">Vehicle alerts</option>
        </select>
        <label className="checkbox-label">
          <input type="checkbox" checked={reads} onChange={(e) => setReads(e.target.checked)} />
          Include data access
        </label>
        <span className="toolbar-right subtle">{items?.length || 0} recent records</span>
      </div>
      <section className="workspace-panel">
        <div className="table-scroll">
          <table className="data-table audit-table">
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Detail</th>
                <th>Resource</th>
              </tr>
            </thead>
            <tbody>
              {items?.map((a) => (
                <tr key={a.id}>
                  <td className="nowrap muted">{dateTime(a.createdAt)}</td>
                  <td>
                    <span className="actor-name">
                      <span className="mini-avatar">
                        {a.actor === 'system' ? 'S' : a.actor.substring(0, 1).toUpperCase()}
                      </span>
                      {a.actor.split('@')[0]}
                    </span>
                  </td>
                  <td>
                    <span className="audit-action">{a.action.toLowerCase().replaceAll('_', ' ')}</span>
                  </td>
                  <td>{a.detail}</td>
                  <td className="mono muted">
                    {a.resource.length > 18 ? a.resource.substring(0, 12) + '...' : a.resource}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {query.isPending ? (
            <Loading />
          ) : !items?.length ? (
            <Empty title="No matching audit entries" />
          ) : null}
        </div>
      </section>
    </>
  );
}
