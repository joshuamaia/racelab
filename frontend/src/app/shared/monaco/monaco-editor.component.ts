import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type * as Monaco from 'monaco-editor';
import { SkeletonModule } from 'primeng/skeleton';
import { MonacoLoaderService, monacoLanguage } from './monaco-loader.service';

/** Read-only Monaco editor (lazy-loaded). */
@Component({
  selector: 'rl-monaco-editor',
  imports: [SkeletonModule],
  template: `
    <div class="monaco-wrap" [style.height]="height()">
      @if (!ready() && !failed()) {
        <p-skeleton width="100%" height="100%" />
      }
      @if (failed()) {
        <pre class="code-block fallback" [attr.aria-label]="ariaLabel()">{{ value() }}</pre>
      }
      <div #host class="monaco-host" [class.hidden]="!ready()" [attr.aria-label]="ariaLabel()"></div>
    </div>
  `,
  styles: `
    :host { display: block; }
    .monaco-wrap { position: relative; }
    .monaco-host { position: absolute; inset: 0; }
    .monaco-host.hidden { visibility: hidden; }
    .fallback { height: 100%; max-height: none; margin: 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MonacoEditorComponent {
  readonly value = input('');
  readonly language = input<string | null>('plaintext');
  readonly height = input('360px');
  readonly ariaLabel = input('Code editor (read only)');

  protected readonly ready = signal(false);
  protected readonly failed = signal(false);
  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private readonly loader = inject(MonacoLoaderService);
  private editor: Monaco.editor.IStandaloneCodeEditor | null = null;
  private monaco: typeof Monaco | null = null;

  constructor() {
    afterNextRender(() => {
      this.loader
        .load()
        .then((monaco) => {
          this.monaco = monaco;
          this.editor = monaco.editor.create(this.host().nativeElement, {
            value: this.value(),
            language: monacoLanguage(this.language()),
            readOnly: true,
            domReadOnly: true,
            automaticLayout: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontFamily: "'JetBrains Mono', ui-monospace, Consolas, monospace",
            fontSize: 13,
            renderLineHighlight: 'none',
            theme: this.loader.themeName,
          });
          this.ready.set(true);
        })
        .catch(() => this.failed.set(true));
    });

    effect(() => {
      const value = this.value();
      const lang = monacoLanguage(this.language());
      if (!this.editor || !this.monaco) return;
      const model = this.editor.getModel();
      if (model && model.getValue() !== value) model.setValue(value);
      if (model) this.monaco.editor.setModelLanguage(model, lang);
    });

    inject(DestroyRef).onDestroy(() => {
      this.editor?.getModel()?.dispose();
      this.editor?.dispose();
    });
  }
}
