import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { BrainCircuit, Loader2, ShieldCheck, Trash2 } from 'lucide-react';
import { api, post } from './api';
import { Badge, Dialog, Empty, Loading, SectionTitle } from './components';
import { useWorkspace } from './context';

interface DriftStream {
  oem: string;
  samples: number;
  probability: number;
  status: string;
  features: Record<string, number>;
}
export function DriftPanel() {
  const query = useQuery<{ streams: DriftStream[]; evaluation: string }>({
    queryKey: ['drift'],
    queryFn: () => api('/ml/drift'),
    refetchInterval: 10000,
  });
  return (
    <section className="workspace-panel drift-panel">
      <SectionTitle title="Drift intelligence" detail="Advisory model / 100-event source windows">
        <BrainCircuit size={20} />
      </SectionTitle>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <Empty title="Drift scoring unavailable" detail={query.error.message} />
      ) : (
        <>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>OEM stream</th>
                  <th>Samples</th>
                  <th>Drift probability</th>
                  <th>Contract change</th>
                  <th>Assessment</th>
                </tr>
              </thead>
              <tbody>
                {query.data.streams.map((s) => (
                  <tr key={s.oem}>
                    <td className="capitalize">{s.oem}</td>
                    <td>{s.samples}</td>
                    <td>
                      <div className="quality-cell">
                        <strong>{(s.probability * 100).toFixed(1)}%</strong>
                        <span className="quality-track">
                          <i
                            style={{
                              width: s.probability * 100 + '%',
                              background: s.status === 'REVIEW' ? '#ad720e' : '#24876a',
                            }}
                          />
                        </span>
                      </div>
                    </td>
                    <td>{s.features.schema_change ? 'Detected' : 'None'}</td>
                    <td>
                      <Badge status={s.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="model-disclosure">
            <ShieldCheck size={14} />
            {query.data.evaluation}. Mapping approval remains a human decision.
          </p>
        </>
      )}
    </section>
  );
}
export function PrivacyPanel() {
  const { user, notify } = useWorkspace();
  const client = useQueryClient();
  const [open, setOpen] = useState(false),
    [vin, setVin] = useState(''),
    [confirmation, setConfirmation] = useState(''),
    [busy, setBusy] = useState(false);
  const query = useQuery<{ id: string; status: string; createdAt: string }[]>({
    queryKey: ['erasures'],
    queryFn: () => api('/privacy/erasures'),
    enabled: user.role === 'ADMIN',
  });
  if (user.role !== 'ADMIN') return null;
  async function erase() {
    setBusy(true);
    try {
      await post('/privacy/erase', { vin, confirmation });
      setOpen(false);
      setVin('');
      setConfirmation('');
      client.invalidateQueries();
      notify('Local evidence removed. External purge verification is pending.');
    } catch (e) {
      notify((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="workspace-panel privacy-panel">
      <SectionTitle title="Privacy requests" detail="Administrator access">
        <button className="button secondary small" onClick={() => setOpen(true)}>
          <Trash2 size={14} />
          Erase vehicle evidence
        </button>
      </SectionTitle>
      {query.data?.length ? (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Request</th>
                <th>Local store</th>
                <th>External verification</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.id.slice(0, 8)}</td>
                  <td>Purged</td>
                  <td>
                    <Badge status="PENDING">Pending</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="No privacy requests" />
      )}
      {open && (
        <Dialog title="Erase vehicle evidence" onClose={() => setOpen(false)}>
          <p className="dialog-description">
            This removes the vehicle and its telemetry, alerts, revisions and pending projections from the
            local database. It cannot be undone here.
          </p>
          <p className="form-error">
            Broker records, external projections, downloaded exports and backups require separate purge
            verification. This request will remain externally pending.
          </p>
          <label className="privacy-field">
            Vehicle VIN
            <input
              value={vin}
              onChange={(e) => setVin(e.target.value.toUpperCase())}
              maxLength={17}
              placeholder="17-character VIN"
            />
          </label>
          <label className="privacy-field">
            Confirm VIN
            <input
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value.toUpperCase())}
              maxLength={17}
            />
          </label>
          <div className="dialog-actions">
            <button className="button secondary" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button
              className="button danger"
              disabled={busy || vin.length !== 17 || vin !== confirmation}
              onClick={erase}
            >
              {busy ? <Loader2 size={14} className="spin" /> : <Trash2 size={14} />}Erase local evidence
            </button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
