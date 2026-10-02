import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Activity, ArrowUpRight, CheckCircle2, Loader2, SearchX, X } from 'lucide-react';
import type { Point } from './types';
export const number = (value: number | undefined) => new Intl.NumberFormat('en-US').format(value || 0);
export const compact = (value: number) =>
  new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
export function relative(value: string | null | undefined) {
  if (!value || value.startsWith('1970')) return 'Not reported';
  const s = Math.max(0, Math.round((Date.now() - Date.parse(value)) / 1000));
  return s < 60
    ? `${s}s ago`
    : s < 3600
      ? `${Math.floor(s / 60)}m ago`
      : s < 86400
        ? `${Math.floor(s / 3600)}h ago`
        : new Date(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}
export const dateTime = (value: string) =>
  new Date(value).toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
export function Badge({ status, children }: { status: string; children?: ReactNode }) {
  const mode = ['HEALTHY', 'VALID', 'ACCEPTED', 'APPROVED', 'COMPLETED', 'RESOLVED', 'RECOVERED'].includes(
    status,
  )
    ? 'green'
    : ['CRITICAL', 'FAILED', 'REJECTED'].includes(status)
      ? 'red'
      : ['DEGRADED', 'WARNING', 'QUARANTINED', 'DRAFT', 'UNTRUSTED', 'PARTIAL', 'OPEN'].includes(status)
        ? 'amber'
        : ['RUNNING', 'ACKNOWLEDGED'].includes(status)
          ? 'blue'
          : 'muted';
  return (
    <span className={`badge ${mode}`}>
      <i />
      {children || status.toLowerCase().replaceAll('_', ' ')}
    </span>
  );
}
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className={`brand ${compact ? 'compact' : ''}`}>
      <span className="brand-symbol">
        <Activity size={21} strokeWidth={2.6} />
      </span>
      {!compact && (
        <>
          fleet<span className="brand-light">truth</span>
          <span className="brand-dot">.</span>
        </>
      )}
    </span>
  );
}
export function Loading({ label = 'Loading workspace' }: { label?: string }) {
  return (
    <div className="loading">
      <Loader2 className="spin" size={22} />
      <span>{label}</span>
    </div>
  );
}
export function Empty({ title, detail, children }: { title: string; detail?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <SearchX size={28} />
      <h3>{title}</h3>
      {detail && <p>{detail}</p>}
      {children}
    </div>
  );
}
export function Dialog({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className={`dialog ${wide ? 'wide' : ''}`}
      onCancel={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="dialog-header">
        <h2>{title}</h2>
        <button className="icon-button" aria-label="Close dialog" onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <div className="dialog-body">{children}</div>
    </dialog>
  );
}
export function PageHeading({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="heading-actions">{children}</div>
    </div>
  );
}
export function SectionTitle({
  title,
  detail,
  children,
}: {
  title: string;
  detail?: string;
  children?: ReactNode;
}) {
  return (
    <div className="section-title">
      <div>
        <h2>{title}</h2>
        {detail && <p>{detail}</p>}
      </div>
      {children}
    </div>
  );
}
export function SignalChart({ data, height = 245 }: { data: Point[]; height?: number }) {
  const id = useId();
  return (
    <div className="signal-chart" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ left: -20, right: 12, top: 12, bottom: 0 }}>
          <defs>
            <linearGradient id={`${id}-accepted`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--green)" stopOpacity={0.2} />
              <stop offset="100%" stopColor="var(--green)" stopOpacity={0.01} />
            </linearGradient>
            <linearGradient id={`${id}-quarantined`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--amber)" stopOpacity={0.15} />
              <stop offset="100%" stopColor="var(--amber)" stopOpacity={0.01} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 5" vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="time"
            tickFormatter={(v) =>
              new Date(v).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
            }
            minTickGap={50}
            axisLine={false}
            tickLine={false}
            tick={{ fontSize: 11, fill: 'var(--text-muted)' }}
            dy={8}
          />
          <YAxis
            tickLine={false}
            axisLine={false}
            tick={{ fontSize: 11, fill: 'var(--text-muted)' }}
            allowDecimals={false}
          />
          <Tooltip
            contentStyle={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border-strong)',
              borderRadius: 8,
              fontSize: 12,
              color: 'var(--text-primary)',
              boxShadow: 'var(--shadow)',
            }}
            labelStyle={{ color: 'var(--text-secondary)', marginBottom: 6 }}
            labelFormatter={(value) => new Date(String(value)).toLocaleTimeString('en-GB')}
          />
          <Area
            type="monotone"
            dataKey="accepted"
            name="Accepted"
            stroke="var(--green)"
            strokeWidth={2}
            fill={`url(#${id}-accepted)`}
            isAnimationActive={false}
          />
          <Area
            type="monotone"
            dataKey="quarantined"
            name="Quarantined"
            stroke="var(--amber)"
            strokeWidth={1.5}
            fill={`url(#${id}-quarantined)`}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
export function LinkButton({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button className="text-button" onClick={onClick}>
      {children}
      <ArrowUpRight size={14} />
    </button>
  );
}
export function Success({ children }: { children: ReactNode }) {
  return (
    <div className="success-message">
      <CheckCircle2 size={17} />
      {children}
    </div>
  );
}
