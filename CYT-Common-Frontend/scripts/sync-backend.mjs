import { cp, mkdir, rm } from 'node:fs/promises';
import { resolve, dirname, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backend = resolve(root, '../CYT-Common-API');
for (const destination of ['src/main/resources/web', 'target/classes/web']) {
  const target = resolve(backend, destination);
  const withinBackend = relative(backend, target);
  if (!withinBackend || withinBackend.startsWith('..') || isAbsolute(withinBackend)) {
    throw new Error('Static asset destination must stay inside the sibling backend.');
  }
  await rm(target, { recursive: true, force: true });
  await mkdir(target, { recursive: true });
  await cp(resolve(root, 'dist'), target, { recursive: true });
}
console.log('Frontend synchronized to sibling CYT-Common-API.');
