import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject } from '@angular/core';
import type * as Monaco from 'monaco-editor';
import { PreferencesService } from '../../core/state/preferences.service';

export type MonacoApi = typeof Monaco;

/** Assets copied from node_modules/monaco-editor/min/vs by angular.json (works offline, no CDN). */
export const MONACO_BASE = 'assets/monaco/vs';

interface AmdRequire {
  (deps: string[], onLoad: () => void, onError?: (err: unknown) => void): void;
  config(cfg: { paths: Record<string, string> }): void;
}

interface MonacoWindow extends Window {
  require?: AmdRequire;
  monaco?: MonacoApi;
  MonacoEnvironment?: { getWorkerUrl: (moduleId: string, label: string) => string };
}

/** Lazily loads Monaco through its AMD loader the first time an editor is displayed. */
@Injectable({ providedIn: 'root' })
export class MonacoLoaderService {
  private readonly document = inject(DOCUMENT);
  private readonly prefs = inject(PreferencesService);
  private promise: Promise<MonacoApi> | null = null;
  private api: MonacoApi | null = null;

  constructor() {
    effect(() => {
      const theme = this.prefs.theme();
      this.api?.editor.setTheme(theme === 'dark' ? 'racelab-dark' : 'racelab-light');
    });
  }

  get themeName(): string {
    return this.prefs.theme() === 'dark' ? 'racelab-dark' : 'racelab-light';
  }

  load(): Promise<MonacoApi> {
    if (this.promise) return this.promise;
    const win = this.document.defaultView as MonacoWindow;
    const base = new URL(MONACO_BASE, this.document.baseURI).href;
    this.promise = new Promise<MonacoApi>((resolve, reject) => {
      if (win.monaco) {
        resolve(this.init(win.monaco));
        return;
      }
      win.MonacoEnvironment = {
        getWorkerUrl: () => `${base}/base/worker/workerMain.js`,
      };
      const script = this.document.createElement('script');
      script.src = `${base}/loader.js`;
      script.async = true;
      script.onload = () => {
        const amd = win.require;
        if (!amd) {
          reject(new Error('Monaco AMD loader not available'));
          return;
        }
        amd.config({ paths: { vs: base } });
        amd(
          ['vs/editor/editor.main'],
          () => (win.monaco ? resolve(this.init(win.monaco)) : reject(new Error('Monaco failed to initialise'))),
          (err) => reject(err),
        );
      };
      script.onerror = () => reject(new Error('Could not load Monaco editor assets'));
      this.document.head.appendChild(script);
    }).catch((err) => {
      this.promise = null;
      throw err;
    });
    return this.promise;
  }

  private init(monaco: MonacoApi): MonacoApi {
    if (this.api) return monaco;
    monaco.editor.defineTheme('racelab-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '6b7a99', fontStyle: 'italic' },
        { token: 'keyword', foreground: '60a5fa' },
        { token: 'string', foreground: '4ade80' },
        { token: 'number', foreground: 'facc15' },
        { token: 'type', foreground: '22d3ee' },
      ],
      colors: {
        'editor.background': '#0e1628',
        'editor.lineHighlightBackground': '#151f35',
        'editorLineNumber.foreground': '#3d4b69',
        'editorGutter.background': '#0e1628',
        'diffEditor.insertedTextBackground': '#22c55e22',
        'diffEditor.removedTextBackground': '#ef444422',
      },
    });
    monaco.editor.defineTheme('racelab-light', {
      base: 'vs',
      inherit: true,
      rules: [],
      colors: { 'editor.background': '#f8fafd' },
    });
    monaco.editor.setTheme(this.themeName);
    this.api = monaco;
    return monaco;
  }
}

/** Maps contract language names to Monaco language ids. */
export function monacoLanguage(language: string | null | undefined): string {
  switch ((language ?? '').toLowerCase()) {
    case 'java':
      return 'java';
    case 'sql':
    case 'postgresql':
      return 'sql';
    case 'typescript':
    case 'ts':
      return 'typescript';
    case 'json':
      return 'json';
    case 'yaml':
    case 'yml':
      return 'yaml';
    case 'properties':
      return 'ini';
    case 'markdown':
    case 'md':
      return 'markdown';
    default:
      return 'plaintext';
  }
}
