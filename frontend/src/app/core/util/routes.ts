import { Category } from '../models/api.models';

/** Route of the lab for a category. */
export function labRoute(category: Category): string {
  return `/lab/${category.toLowerCase()}`;
}

export const CATEGORY_LABELS: Record<Category, string> = {
  CONCURRENCY: 'Concurrency',
  ISOLATION: 'Isolation',
  LOCKING: 'Locking',
  DISTRIBUTED: 'Distributed',
};
