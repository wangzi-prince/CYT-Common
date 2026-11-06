import type { Schema, Operation } from '~/api/types';
import fixtures from '~/data/operation-examples.json';
const examples: Record<string, unknown> = fixtures;
export function operationExample(operation: Operation): unknown {
  return structuredClone(
    operation.schema.examples?.[0] ?? examples[operation.key] ?? example(operation.schema),
  );
}
export function example(schema: Schema, root = schema, depth = 0): unknown {
  if (depth > 8) return {};
  if (schema.default !== undefined) return schema.default;
  if (schema.$ref)
    return example(root.$defs?.[schema.$ref.split('/').pop() || ''] || {}, root, depth + 1);
  if (schema.enum) return schema.enum[0];
  if (schema.anyOf)
    return example(schema.anyOf.find((v) => v.type !== 'null') || schema.anyOf[0], root, depth + 1);
  if (schema.type === 'object' || schema.properties)
    return Object.fromEntries(
      Object.entries(schema.properties || {})
        .filter(([key, value]) => schema.required?.includes(key) || value.default !== undefined)
        .map(([key, value]) => [key, example(value, root, depth + 1)]),
    );
  if (schema.type === 'array') return [];
  if (schema.type === 'integer' || schema.type === 'number') return 1;
  if (schema.type === 'boolean') return false;
  return '';
}
