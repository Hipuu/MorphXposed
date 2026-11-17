// Extracts every inline `run:` block from the workflow so bash can syntax-check it.
const fs = require('fs');
const path = require('path');

const target = process.argv[2];
const outDir = process.argv[3];
if (!target || !outDir) {
  console.log('  usage: extract.js <workflow.yml> <out-dir>');
  process.exit(2);
}

let yaml;
try {
  yaml = require('js-yaml');
} catch (e) {
  console.log('  SKIP: js-yaml is not installed');
  process.exit(0);
}

const doc = yaml.load(fs.readFileSync(target, 'utf8'));
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });

// The job name is part of the filename. Two jobs can have a step at the same index with the same
// title - the test job and the release job both fetch the bundle - and without the prefix the
// second silently overwrites the first, leaving one block unchecked.
let n = 0;
const collisions = [];
const seen = new Set();
for (const [jobName, job] of Object.entries(doc.jobs || {})) {
  (job.steps || []).forEach((s, i) => {
    if (!s.run) return;
    n += 1;
    const safeJob = jobName.replace(/[^A-Za-z0-9]+/g, '-');
    const name = (s.name || `unnamed-${i + 1}`).replace(/[^A-Za-z0-9]+/g, '-');
    const file = path.join(outDir, `${safeJob}-step${i + 1}-${name}.sh`);
    if (seen.has(file)) collisions.push(path.basename(file));
    seen.add(file);
    fs.writeFileSync(file, s.run, 'utf8');
    console.log(`  ${path.basename(file)}`);
  });
}
if (collisions.length) {
  // Would mean two different blocks share a path, so one is never checked.
  collisions.forEach((c) => console.log(`  CONFLICT: ${c}`));
  process.exit(1);
}
console.log(`  ${n} run block(s)`);
