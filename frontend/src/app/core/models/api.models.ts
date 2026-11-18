/**
 * TypeScript mirror of docs/API-CONTRACT.md. Keep 1:1 with the contract.
 */

export type Category = 'CONCURRENCY' | 'ISOLATION' | 'LOCKING' | 'DISTRIBUTED';
export const CATEGORIES: readonly Category[] = ['CONCURRENCY', 'ISOLATION', 'LOCKING', 'DISTRIBUTED'];

export type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export type StrategyType =
  | 'NONE'
  | 'OPTIMISTIC_LOCKING'
  | 'PESSIMISTIC_LOCKING'
  | 'ATOMIC_UPDATE'
  | 'ATOMIC_UNGUARDED'
  | 'SERIALIZABLE'
  | 'JAVA_SYNCHRONIZED'
  | 'REENTRANT_LOCK'
  | 'ADVISORY_LOCK'
  | 'REDIS_LOCK'
  | 'FENCING_TOKEN'
  | 'UNIQUE_CONSTRAINT'
  | 'IDEMPOTENCY_KEY'
  | 'LOCK_ORDERING'
  | 'ATOMIC_CLAIM'
  | 'SKIP_LOCKED'
  | 'OUTBOX'
  | 'OUTBOX_NAIVE_CONSUMER'
  | 'ISOLATION_LEVEL';

export type IsolationLevel = 'READ_UNCOMMITTED' | 'READ_COMMITTED' | 'REPEATABLE_READ' | 'SERIALIZABLE';
export const ISOLATION_LEVELS: readonly IsolationLevel[] = [
  'READ_UNCOMMITTED',
  'READ_COMMITTED',
  'REPEATABLE_READ',
  'SERIALIZABLE',
];

export type ExecutionMode = 'CONCURRENT' | 'STEP_BY_STEP';
export type Topology = 'SINGLE_JVM' | 'DISTRIBUTED';
export type FailureMode = 'NONE' | 'CRASH_AFTER_COMMIT' | 'CRASH_BEFORE_COMMIT' | 'RELAY_CRASH_BEFORE_ACK';
export type ExperimentStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export const EXPERIMENT_STATUSES: readonly ExperimentStatus[] = ['CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'];
export const TERMINAL_STATUSES: readonly ExperimentStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED'];

export type EventSourceKind = 'INSTRUMENTED' | 'DATABASE_OBSERVED';
export type TxStatus = 'COMMITTED' | 'ROLLED_BACK' | 'REJECTED' | 'FAILED' | 'CANCELLED';
export type ErrorType =
  | 'DEADLOCK'
  | 'SERIALIZATION_FAILURE'
  | 'OPTIMISTIC_LOCK'
  | 'LOCK_TIMEOUT'
  | 'UNIQUE_VIOLATION'
  | 'TRANSACTION_TIMEOUT'
  | 'CONNECTION_FAILURE'
  | 'CANCELLED'
  | 'BUSINESS_RULE'
  | 'LOCK_NOT_ACQUIRED'
  | 'INJECTED_FAULT'
  | 'INTERNAL';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type EventType =
  | 'EXPERIMENT_CREATED'
  | 'EXPERIMENT_STARTED'
  | 'DATA_PREPARED'
  | 'TRANSACTION_STARTED'
  | 'READ_STARTED'
  | 'READ_COMPLETED'
  | 'VALIDATION_STARTED'
  | 'VALIDATION_COMPLETED'
  | 'LOCK_REQUESTED'
  | 'LOCK_ACQUIRED'
  | 'LOCK_WAITING'
  | 'LOCK_RELEASED'
  | 'WRITE_STARTED'
  | 'WRITE_COMPLETED'
  | 'COMMIT_STARTED'
  | 'COMMIT_COMPLETED'
  | 'ROLLBACK_STARTED'
  | 'ROLLBACK_COMPLETED'
  | 'DEADLOCK_DETECTED'
  | 'OPTIMISTIC_LOCK_CONFLICT'
  | 'SERIALIZATION_FAILURE'
  | 'RETRY_SCHEDULED'
  | 'INCONSISTENCY_DETECTED'
  | 'CHECKPOINT_REACHED'
  | 'CHECKPOINT_RELEASED'
  | 'MESSAGE_PUBLISHED'
  | 'MESSAGE_CONSUMED'
  | 'FAULT_INJECTED'
  | 'EXPERIMENT_COMPLETED'
  | 'EXPERIMENT_FAILED'
  | 'EXPERIMENT_CANCELLED';

export const EVENT_TYPES: readonly EventType[] = [
  'EXPERIMENT_CREATED',
  'EXPERIMENT_STARTED',
  'DATA_PREPARED',
  'TRANSACTION_STARTED',
  'READ_STARTED',
  'READ_COMPLETED',
  'VALIDATION_STARTED',
  'VALIDATION_COMPLETED',
  'LOCK_REQUESTED',
  'LOCK_ACQUIRED',
  'LOCK_WAITING',
  'LOCK_RELEASED',
  'WRITE_STARTED',
  'WRITE_COMPLETED',
  'COMMIT_STARTED',
  'COMMIT_COMPLETED',
  'ROLLBACK_STARTED',
  'ROLLBACK_COMPLETED',
  'DEADLOCK_DETECTED',
  'OPTIMISTIC_LOCK_CONFLICT',
  'SERIALIZATION_FAILURE',
  'RETRY_SCHEDULED',
  'INCONSISTENCY_DETECTED',
  'CHECKPOINT_REACHED',
  'CHECKPOINT_RELEASED',
  'MESSAGE_PUBLISHED',
  'MESSAGE_CONSUMED',
  'FAULT_INJECTED',
  'EXPERIMENT_COMPLETED',
  'EXPERIMENT_FAILED',
  'EXPERIMENT_CANCELLED',
];

export type ParameterKey = keyof ExperimentConfiguration;

export interface ExperimentConfiguration {
  threads: number;
  transactions: number;
  initialValue: number;
  operationValue: number;
  resourceCount: number;
  resourceCode: string;
  delayBeforeReadMs: number;
  delayAfterReadMs: number;
  delayBeforeWriteMs: number;
  delayBeforeCommitMs: number;
  isolationLevel: IsolationLevel;
  transactionTimeoutMs: number;
  lockTimeoutMs: number;
  retries: number;
  backoffMs: number;
  seed: number;
  executionMode: ExecutionMode;
  topology: Topology;
  coordinated: boolean;
  lockTtlMs: number;
  failureMode: FailureMode;
  stepTimeoutSeconds: number;
}

export interface ScenarioTemplate {
  code: string;
  label: string;
}

export interface ScenarioDescriptor {
  id: string;
  name: string;
  category: Category;
  description: string;
  businessContext: string;
  invariant: string;
  supportedStrategies: StrategyType[];
  defaultStrategy: StrategyType;
  supportsStepMode: boolean;
  supportsDistributed: boolean;
  relevantParameters: ParameterKey[];
  parameterLabels: Partial<Record<ParameterKey, string>>;
  templates: ScenarioTemplate[];
  defaults: ExperimentConfiguration;
}

export interface ExperimentPreset {
  id: string;
  name: string;
  category: Category;
  difficulty: Difficulty;
  problem: string;
  businessScenario: string;
  scenarioId: string;
  strategy: StrategyType;
  availableStrategies: StrategyType[];
  configuration: ExperimentConfiguration;
  expectedResult: string;
  explanation: string;
  recommendations: string[];
  demo: number | null;
  tags: string[];
}

export interface StrategyDescriptor {
  id: StrategyType;
  name: string;
  family: string;
  scope: string;
  description: string;
  advantages: string[];
  limitations: string[];
}

export interface CreateExperimentRequest {
  presetId?: string | null;
  scenarioId: string;
  strategy: StrategyType;
  name?: string | null;
  configuration?: Partial<ExperimentConfiguration>;
  autoStart?: boolean;
}

export interface PendingCheckpoint {
  workerId: string;
  step: string;
  description: string;
  arrivedAt: string;
}

export interface BlockedWorker {
  workerId: string;
  reason: string;
}

export interface StepState {
  active: boolean;
  pending: PendingCheckpoint[];
  blocked: BlockedWorker[];
  suggestedWorkerId: string | null;
  deadline: string | null;
}

export interface ExperimentDetails {
  id: string;
  name: string;
  presetId: string | null;
  scenarioId: string;
  category: Category;
  strategy: StrategyType;
  status: ExperimentStatus;
  executionMode: ExecutionMode;
  instanceId: string | null;
  comparisonId: string | null;
  warmup: boolean;
  rerunOf: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  consistent: boolean | null;
  violationCount: number | null;
  eventCount: number | null;
  configuration: ExperimentConfiguration;
  errorMessage: string | null;
  stepState: StepState | null;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ExperimentListQuery {
  page: number;
  size: number;
  category?: Category | null;
  status?: ExperimentStatus | null;
  scenarioId?: string | null;
  strategy?: StrategyType | null;
}

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = Record<string, JsonValue>;

export interface ExperimentEvent {
  experimentId: string;
  sequence: number;
  transactionId: string | null;
  workerId: string | null;
  threadName: string | null;
  instanceId: string | null;
  correlationId: string | null;
  connectionPid: number | null;
  eventType: EventType;
  resource: string | null;
  timestamp: string;
  elapsedTimeMs: number;
  source: EventSourceKind;
  message: string | null;
  details: JsonObject | null;
}

export interface ResourceState {
  resource: string;
  label: string;
  type: string;
  attributes: JsonObject;
}

export interface StateRow {
  resource: string;
  label: string;
  attribute: string;
  initial: string | null;
  actual: string | null;
  expected: string | null;
  ok: boolean;
}

export interface TransactionOutcome {
  workerId: string;
  transactionId: string;
  instanceId: string | null;
  status: TxStatus;
  attempts: number;
  latencyMs: number;
  lockWaitMs: number;
  sqlState: string | null;
  errorType: ErrorType | null;
  message: string | null;
  friendlyMessage: string | null;
  expectedFailure: boolean;
  before: JsonObject | null;
  after: JsonObject | null;
  details: JsonObject | null;
}

export interface Violation {
  kind: string;
  rule: string;
  description: string;
  expected: string | null;
  actual: string | null;
  difference: string | null;
  transactions: string[];
  severity: Severity;
  possibleCause: string | null;
  recommendedStrategies: StrategyType[];
}

export interface Observation {
  phenomenon: string;
  isolationLevel: IsolationLevel;
  effectiveIsolationLevel: IsolationLevel;
  reproduced: boolean;
  outcome: string;
  values: JsonObject | null;
  note: string | null;
}

export interface Metrics {
  totalTimeMs: number;
  avgLatencyMs: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  maxLatencyMs: number;
  throughputTps: number;
  transactionsStarted: number;
  attempts: number;
  committed: number;
  rolledBack: number;
  businessRejections: number;
  conflicts: number;
  deadlocks: number;
  serializationFailures: number;
  optimisticConflicts: number;
  uniqueViolations: number;
  lockTimeouts: number;
  retries: number;
  lockWaitTimeMs: number;
  inconsistencies: number;
}

export interface Recommendation {
  id: string;
  title: string;
  trigger: string;
  justification: string;
  benefits: string[];
  tradeOffs: string[];
  strategies: StrategyType[];
  priority: number;
}

export type LockGraphSource = 'DATABASE_REPORTED' | 'DATABASE_OBSERVED' | 'RECONSTRUCTED';

export interface LockGraphNode {
  id: string;
  kind: 'TRANSACTION' | 'LOCK';
  label: string;
  workerId: string | null;
  pid: number | null;
  victim: boolean;
}

export interface LockGraphEdge {
  from: string;
  to: string;
  label: string | null;
}

export interface LockGraph {
  source: LockGraphSource;
  description: string | null;
  cycle: boolean;
  nodes: LockGraphNode[];
  edges: LockGraphEdge[];
}

export interface ExperimentResult {
  experimentId: string;
  status: ExperimentStatus;
  consistent: boolean;
  summary: string;
  initialState: ResourceState[];
  finalState: ResourceState[];
  stateRows: StateRow[];
  transactions: TransactionOutcome[];
  violations: Violation[];
  observations: Observation[];
  metrics: Metrics;
  recommendations: Recommendation[];
  lockGraph: LockGraph | null;
  environment: Record<string, string | number | boolean | null>;
}

// ---- Comparisons --------------------------------------------------------

export type ComparisonStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface CreateComparisonRequest {
  name?: string | null;
  scenarioId: string;
  strategies: StrategyType[];
  configuration?: Partial<ExperimentConfiguration>;
  repetitions: number;
  warmupRuns: number;
}

export interface ComparisonProgress {
  completedRuns: number;
  totalRuns: number;
  currentStrategy: StrategyType | null;
}

export interface ComparisonStrategyResult {
  strategy: StrategyType;
  experimentIds: string[];
  measuredRuns: number;
  metrics: Metrics;
  consistentRuns: number;
  inconsistentRuns: number;
  consistencyPreserved: boolean;
  finalStateRows: StateRow[];
}

export interface Comparison {
  id: string;
  name: string | null;
  scenarioId: string;
  status: ComparisonStatus;
  strategies: StrategyType[];
  configuration: Partial<ExperimentConfiguration>;
  repetitions: number;
  warmupRuns: number;
  progress: ComparisonProgress | null;
  createdAt: string;
  finishedAt: string | null;
  results: ComparisonStrategyResult[];
  disclaimer: string | null;
}

// ---- Code examples ------------------------------------------------------

export interface CodeSnippet {
  title: string;
  source: string;
  language: string;
  code: string;
}

export interface CodeExample {
  scenarioId: string;
  strategy: StrategyType;
  java: CodeSnippet[];
  sql: CodeSnippet[];
  baseline: { strategy: StrategyType; java: CodeSnippet[]; sql: CodeSnippet[] } | null;
  typescript: CodeSnippet | null;
  configuration: ExperimentConfiguration | null;
  explanation: string;
  advantages: string[];
  limitations: string[];
}

// ---- Dashboard ----------------------------------------------------------

export interface DashboardTotals {
  experiments: number;
  transactions: number;
  inconsistencies: number;
  deadlocks: number;
  rollbacks: number;
  versionConflicts: number;
  avgDurationMs: number;
  successRate: number;
  running: number;
}

export interface Dashboard {
  totals: DashboardTotals;
  byCategory: { category: Category; count: number }[];
  durationByStrategy: { strategy: StrategyType; avgMs: number; runs: number }[];
  failureRateByStrategy: { strategy: StrategyType; rate: number; runs: number }[];
  inconsistenciesOverTime: { bucket: string; experiments: number; inconsistencies: number }[];
  recent: ExperimentDetails[];
  dataNote: string;
}

// ---- Inspector ----------------------------------------------------------

export type InspectorScope = 'racelab' | 'database';

export interface InspectorSession {
  pid: number;
  applicationName: string | null;
  experimentId: string | null;
  workerId: string | null;
  state: string | null;
  backendXid: string | null;
  xactStart: string | null;
  queryStart: string | null;
  transactionAgeMs: number | null;
  waitEventType: string | null;
  waitEvent: string | null;
  query: string | null;
  blockedBy: number[];
}

export interface InspectorLock {
  pid: number;
  lockType: string;
  mode: string;
  granted: boolean;
  relation: string | null;
  transactionId: string | null;
  tuple: number | null;
  page: number | null;
}

export interface InspectorSnapshot {
  capturedAt: string;
  scope: InspectorScope;
  postgresVersion: string | null;
  sessions: InspectorSession[];
  locks: InspectorLock[];
  graph: LockGraph | null;
  note: string;
}

// ---- System -------------------------------------------------------------

export interface SystemInfo {
  instanceId: string;
  applicationVersion: string;
  javaVersion: string;
  postgresVersion: string;
  redisAvailable: boolean;
  peers: { instanceId: string; url: string; available: boolean }[];
  limits: {
    maxThreads: number;
    maxTransactions: number;
    maxConcurrentExperiments: number;
    maxDelayMs: number;
    maxRetries: number;
  };
  activeExperiments: number;
}

export interface InstanceStatus {
  instanceId: string;
  url: string;
  available: boolean;
  info: SystemInfo | null;
}

export interface ApiError {
  timestamp?: string;
  status: number;
  error?: string;
  code: string;
  message: string;
  details?: string[];
  correlationId?: string;
  path?: string;
}

export type ExportFormat = 'json' | 'csv' | 'markdown' | 'pdf';
