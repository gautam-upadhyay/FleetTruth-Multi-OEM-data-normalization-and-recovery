export type Row = Record<string, unknown>;
export interface User {
  email: string;
  name: string;
  role: string;
  tenantId: string;
}
export interface Oem {
  id: string;
  name: string;
  color: string;
  vehicles: number;
  events: number;
  quarantined: number;
  quality: number;
  status: string;
  lastSeen: string | null;
}
export interface Alert {
  id: string;
  vin: string;
  registration: string;
  model: string;
  oemId: string;
  oemName: string;
  oemColor: string;
  title: string;
  description: string;
  severity: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  eventId: string;
  assignedTo: string | null;
}
export interface Incident {
  id: string;
  oemId: string;
  oemName: string;
  schemaVersion: string;
  title: string;
  description: string;
  status: string;
  createdAt: string;
}
export interface Point {
  time: string;
  accepted: number;
  quarantined: number;
}
export interface Overview {
  vehicles: number;
  reporting: number;
  processed: number;
  accepted: number;
  quarantined: number;
  quality: number;
  duplicates: number;
  openAlerts: number;
  criticalAlerts: number;
  openIncidents: number;
  eventsPerSecond: number;
  timestamp: string;
  series: Point[];
  oems: Oem[];
  incidents: Incident[];
  alerts: Alert[];
}
export interface Vehicle {
  vin: string;
  registration: string;
  model: string;
  driverName: string;
  fleetName: string;
  oemId: string;
  oemName: string;
  oemColor: string;
  eventTime: string | null;
  speedKmh: number | null;
  socPct: number | null;
  quality: string | null;
  latitude: number | null;
  longitude: number | null;
  events?: VehicleEvent[];
  alerts?: Alert[];
  odometerKm?: number;
}
export interface VehicleEvent {
  id: string;
  eventTime: string;
  status: string;
  reason: string | null;
  payload: string;
  normalized: string | null;
  mappingId: string | null;
}
export interface Rule {
  field: string;
  source: string;
  scale: number;
  offset: number;
  required: boolean;
}
export interface Mapping {
  id: string;
  oemId: string;
  oemName: string;
  oemColor: string;
  schemaVersion: string;
  version: number;
  status: string;
  rules: Rule[];
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
}
export interface Preview {
  total: number;
  valid: number;
  invalid: number;
  rules: Rule[];
  samples: {
    eventId: string;
    vin: string;
    raw: Row;
    normalized: Row | null;
    valid: boolean;
    issues: string[];
  }[];
}
export interface Replay {
  id: string;
  oemId: string;
  status: string;
  total: number;
  recovered: number;
  failed: number;
  requestedBy: string;
  createdAt: string;
  completedAt: string | null;
}
export interface Audit {
  id: string;
  actor: string;
  action: string;
  resource: string;
  detail: string;
  createdAt: string;
}
export interface Simulation {
  running: boolean;
  ready: boolean;
  enrolled: number;
  targetEventsPerSecond: number;
  generatedThisSession: number;
  mode: string;
  helixSchema: number;
}
export interface AssistantReply {
  answer: string;
  tools: { name: string; result: string; status: string }[];
  action: string;
  target: string;
  mode: string;
  timestamp: string;
}
export type View =
  'overview' | 'vehicles' | 'alerts' | 'integrations' | 'mappings' | 'recovery' | 'analytics' | 'audit';
