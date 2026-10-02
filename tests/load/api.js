import http from 'k6/http';
import {check} from 'k6';
export const options = {
  scenarios: {reads: {executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20), timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 20, maxVUs: 100}},
  thresholds: {http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<200', 'p(99)<500'], dropped_iterations: ['count==0']},
};
export default function () {
  const path = ['/overview','/vehicles?limit=25','/alerts'][__ITER % 3];
  const response = http.get(`${__ENV.API_URL || 'http://localhost:8082'}/api${path}`, {headers:{Authorization:`Bearer ${__ENV.FLEETTRUTH_TOKEN}`}});
  check(response, {'authorized response': r=>r.status===200});
}
