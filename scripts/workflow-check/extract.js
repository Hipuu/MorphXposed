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

let n = 0;
Object.values(doc.jobs || {}).forEach((job) => {
  (job.steps || []).forEach((s, i) => {
    if (!s.run) return;
    n += 1;
    const name = (s.name || `unnamed-${i + 1}`).replace(/[^A-Za-z0-9]+/g, '-');
    const file = path.join(outDir, `step${i + 1}-${name}.sh`);
    fs.writeFileSync(file, s.run, 'utf8');
    console.log(`  ${path.basename(file)}`);
  });
});
console.log(`  ${n} run block(s)`);
