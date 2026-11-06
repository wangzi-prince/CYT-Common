export interface ModuleOperation {
  key: string;
  category?: string | null;
}

export function operationModule(operation: ModuleOperation) {
  return operation.category?.trim() || '未分类';
}

/** Module names come from the current catalog; other filters only change their result counts. */
export function operationModules(
  operations: ModuleOperation[],
  matching: ModuleOperation[] = operations,
) {
  const matches = new Set(matching.map((operation) => operation.key));
  const modules = new Map<string, { name: string; total: number; count: number }>();
  for (const operation of operations) {
    const name = operationModule(operation);
    const module = modules.get(name) || { name, total: 0, count: 0 };
    module.total++;
    if (matches.has(operation.key)) module.count++;
    modules.set(name, module);
  }
  return [...modules.values()];
}

export function filterOperationModule<T extends ModuleOperation>(operations: T[], module: string) {
  return module
    ? operations.filter((operation) => operationModule(operation) === module)
    : operations;
}
