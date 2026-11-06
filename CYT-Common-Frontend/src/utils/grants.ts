import type { Operation } from '../api/types';

export function filterGrantOperations(
  operations: Operation[],
  options: { query: string; platform: string; state: string; kind: string },
  allowed: ReadonlySet<string>,
  platformNames: Record<string, string> = {},
) {
  const words = options.query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return operations.filter((op) => {
    const text =
      `${op.key} ${op.summary} ${op.description} ${op.category} ${platformNames[op.platform] || ''} ${(op.tags || []).join(' ')}`.toLowerCase();
    return (
      (!options.platform || op.platform === options.platform) &&
      (options.state === 'all' || allowed.has(op.key) === (options.state === 'allowed')) &&
      (options.kind === 'all' ||
        (options.kind === 'read' ? op.tags?.includes('read') : op.sensitive)) &&
      words.every((word) => text.includes(word))
    );
  });
}

export function updateGrantSelection(
  current: ReadonlySet<string>,
  keys: string[],
  checked: boolean,
) {
  const next = new Set(current);
  keys.forEach((key) => (checked ? next.add(key) : next.delete(key)));
  return next;
}
