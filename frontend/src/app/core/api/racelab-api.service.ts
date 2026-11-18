import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CodeExample,
  Comparison,
  CreateComparisonRequest,
  CreateExperimentRequest,
  Dashboard,
  ExperimentDetails,
  ExperimentEvent,
  ExperimentListQuery,
  ExperimentPreset,
  ExperimentResult,
  ExportFormat,
  InspectorScope,
  InspectorSnapshot,
  InstanceStatus,
  Metrics,
  Page,
  Recommendation,
  ScenarioDescriptor,
  StrategyDescriptor,
  StrategyType,
  SystemInfo,
  TransactionOutcome,
  Violation,
} from '../models/api.models';
import { apiContext } from './http-context';

export const API_BASE = '/api';

export interface RequestOptions {
  silent?: boolean;
  instance?: string | null;
}

/** Thin typed wrapper over every endpoint in docs/API-CONTRACT.md. */
@Injectable({ providedIn: 'root' })
export class RacelabApi {
  private readonly http = inject(HttpClient);

  private ctx(o?: RequestOptions) {
    return { context: apiContext(o ?? {}) };
  }

  // Catalog
  scenarios(o?: RequestOptions): Observable<ScenarioDescriptor[]> {
    return this.http.get<ScenarioDescriptor[]>(`${API_BASE}/scenarios`, this.ctx(o));
  }
  scenario(id: string, o?: RequestOptions): Observable<ScenarioDescriptor> {
    return this.http.get<ScenarioDescriptor>(`${API_BASE}/scenarios/${encodeURIComponent(id)}`, this.ctx(o));
  }
  catalog(o?: RequestOptions): Observable<ExperimentPreset[]> {
    return this.http.get<ExperimentPreset[]>(`${API_BASE}/catalog`, this.ctx(o));
  }
  strategies(o?: RequestOptions): Observable<StrategyDescriptor[]> {
    return this.http.get<StrategyDescriptor[]>(`${API_BASE}/strategies`, this.ctx(o));
  }

  // Experiments
  createExperiment(body: CreateExperimentRequest, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${API_BASE}/experiments`, body, this.ctx(o));
  }
  listExperiments(q: ExperimentListQuery, o?: RequestOptions): Observable<Page<ExperimentDetails>> {
    let params = new HttpParams().set('page', q.page).set('size', q.size);
    if (q.category) params = params.set('category', q.category);
    if (q.status) params = params.set('status', q.status);
    if (q.scenarioId) params = params.set('scenarioId', q.scenarioId);
    if (q.strategy) params = params.set('strategy', q.strategy);
    return this.http.get<Page<ExperimentDetails>>(`${API_BASE}/experiments`, { params, ...this.ctx(o) });
  }
  experiment(id: string, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.get<ExperimentDetails>(this.exp(id), this.ctx(o));
  }
  start(id: string, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${this.exp(id)}/start`, null, this.ctx(o));
  }
  step(id: string, workerId?: string | null, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${this.exp(id)}/step`, workerId ? { workerId } : {}, this.ctx(o));
  }
  resume(id: string, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${this.exp(id)}/resume`, null, this.ctx(o));
  }
  cancel(id: string, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${this.exp(id)}/cancel`, null, this.ctx(o));
  }
  rerun(id: string, autoStart = false, o?: RequestOptions): Observable<ExperimentDetails> {
    return this.http.post<ExperimentDetails>(`${this.exp(id)}/rerun`, { autoStart }, this.ctx(o));
  }
  deleteExperiment(id: string, o?: RequestOptions): Observable<void> {
    return this.http.delete<void>(this.exp(id), this.ctx(o));
  }
  events(id: string, o?: RequestOptions): Observable<ExperimentEvent[]> {
    return this.http.get<ExperimentEvent[]>(`${this.exp(id)}/events`, {
      headers: { Accept: 'application/json' },
      ...this.ctx(o),
    });
  }
  result(id: string, o?: RequestOptions): Observable<ExperimentResult> {
    return this.http.get<ExperimentResult>(`${this.exp(id)}/result`, this.ctx(o));
  }
  metrics(id: string, o?: RequestOptions): Observable<Metrics> {
    return this.http.get<Metrics>(`${this.exp(id)}/metrics`, this.ctx(o));
  }
  transactions(id: string, o?: RequestOptions): Observable<TransactionOutcome[]> {
    return this.http.get<TransactionOutcome[]>(`${this.exp(id)}/transactions`, this.ctx(o));
  }
  violations(id: string, o?: RequestOptions): Observable<Violation[]> {
    return this.http.get<Violation[]>(`${this.exp(id)}/violations`, this.ctx(o));
  }
  recommendations(id: string, o?: RequestOptions): Observable<Recommendation[]> {
    return this.http.get<Recommendation[]>(`${this.exp(id)}/recommendations`, this.ctx(o));
  }
  exportExperiment(id: string, format: ExportFormat, o?: RequestOptions): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.exp(id)}/export`, {
      params: { format },
      observe: 'response',
      responseType: 'blob',
      ...this.ctx(o),
    });
  }
  /** URL of the SSE stream (EventSource cannot send custom headers, so the instance travels as a query param). */
  eventStreamUrl(id: string, instance?: string | null): string {
    const base = `${this.exp(id)}/events`;
    return instance ? `${base}?instance=${encodeURIComponent(instance)}` : base;
  }

  // Comparisons
  createComparison(body: CreateComparisonRequest, o?: RequestOptions): Observable<Comparison> {
    return this.http.post<Comparison>(`${API_BASE}/comparisons`, body, this.ctx(o));
  }
  comparisons(o?: RequestOptions): Observable<Comparison[]> {
    return this.http.get<Comparison[]>(`${API_BASE}/comparisons`, this.ctx(o));
  }
  comparison(id: string, o?: RequestOptions): Observable<Comparison> {
    return this.http.get<Comparison>(`${API_BASE}/comparisons/${encodeURIComponent(id)}`, this.ctx(o));
  }
  cancelComparison(id: string, o?: RequestOptions): Observable<Comparison> {
    return this.http.post<Comparison>(`${API_BASE}/comparisons/${encodeURIComponent(id)}/cancel`, null, this.ctx(o));
  }

  // Code, dashboard, inspector, system
  codeExample(scenarioId: string, strategy: StrategyType, o?: RequestOptions): Observable<CodeExample> {
    return this.http.get<CodeExample>(
      `${API_BASE}/code-examples/${encodeURIComponent(scenarioId)}/${encodeURIComponent(strategy)}`,
      this.ctx(o),
    );
  }
  dashboard(o?: RequestOptions): Observable<Dashboard> {
    return this.http.get<Dashboard>(`${API_BASE}/dashboard`, this.ctx(o));
  }
  inspectorSnapshot(scope: InspectorScope, o?: RequestOptions): Observable<InspectorSnapshot> {
    return this.http.get<InspectorSnapshot>(`${API_BASE}/inspector/snapshot`, { params: { scope }, ...this.ctx(o) });
  }
  systemInfo(o?: RequestOptions): Observable<SystemInfo> {
    return this.http.get<SystemInfo>(`${API_BASE}/system/info`, this.ctx(o));
  }
  instances(o?: RequestOptions): Observable<InstanceStatus[]> {
    return this.http.get<InstanceStatus[]>(`${API_BASE}/instances`, this.ctx(o));
  }

  private exp(id: string): string {
    return `${API_BASE}/experiments/${encodeURIComponent(id)}`;
  }
}
