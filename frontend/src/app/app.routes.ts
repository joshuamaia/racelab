import { Routes } from '@angular/router';
import { Category } from './core/models/api.models';

const lab = (category: Category, title: string) => ({
  loadComponent: () => import('./features/lab/lab-page.component').then((m) => m.LabPageComponent),
  data: { category },
  title: `${title} · RaceLab`,
});

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard · RaceLab',
    loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  { path: 'lab/concurrency', ...lab('CONCURRENCY', 'Concurrency Lab') },
  { path: 'lab/isolation', ...lab('ISOLATION', 'Isolation Lab') },
  { path: 'lab/locking', ...lab('LOCKING', 'Locking Lab') },
  { path: 'lab/distributed', ...lab('DISTRIBUTED', 'Distributed Lab') },
  {
    path: 'comparator',
    title: 'Strategy Comparator · RaceLab',
    loadComponent: () => import('./features/comparator/comparator.component').then((m) => m.ComparatorComponent),
  },
  {
    path: 'inspector',
    title: 'Transaction Inspector · RaceLab',
    loadComponent: () => import('./features/inspector/inspector.component').then((m) => m.InspectorComponent),
  },
  {
    path: 'code',
    title: 'Code Explorer · RaceLab',
    loadComponent: () => import('./features/code-explorer/code-explorer.component').then((m) => m.CodeExplorerComponent),
  },
  {
    path: 'history',
    title: 'Experiment History · RaceLab',
    loadComponent: () => import('./features/history/history.component').then((m) => m.HistoryComponent),
  },
  {
    path: 'experiments/:id',
    title: 'Experiment · RaceLab',
    loadComponent: () =>
      import('./features/experiment-detail/experiment-detail.component').then((m) => m.ExperimentDetailComponent),
  },
  {
    path: 'docs',
    title: 'Documentation · RaceLab',
    loadComponent: () => import('./features/docs/docs.component').then((m) => m.DocsComponent),
  },
  {
    path: 'settings',
    title: 'Settings · RaceLab',
    loadComponent: () => import('./features/settings/settings.component').then((m) => m.SettingsComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];

