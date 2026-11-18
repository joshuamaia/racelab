export interface BackendInstance {
  instanceId: string;
  url: string;
}

export interface BffConfig {
  port: number;
  timeoutMs: number;
  instances: BackendInstance[];
  defaultInstance: string;
  rateLimitPerMinute: number;
  bodyLimit: string;
}

export const BFF_CONFIG = Symbol('BFF_CONFIG');

/** Reads the BFF configuration from environment variables (12-factor). */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): BffConfig {
  const instances: BackendInstance[] = [
    { instanceId: 'primary', url: env.BACKEND_URL ?? 'http://localhost:8080' },
  ];
  if (env.BACKEND_SECONDARY_URL) {
    instances.push({ instanceId: 'secondary', url: env.BACKEND_SECONDARY_URL });
  }
  return {
    port: Number(env.PORT ?? 3000),
    timeoutMs: Number(env.BFF_TIMEOUT_MS ?? 15000),
    instances,
    defaultInstance: 'primary',
    rateLimitPerMinute: Number(env.BFF_RATE_LIMIT_PER_MINUTE ?? 600),
    bodyLimit: env.BFF_BODY_LIMIT ?? '64kb',
  };
}
