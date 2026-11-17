// Parses the release workflow and reports its structure, so a malformed edit fails here instead of
// on the first tag.
const fs = require('fs');
const path = require('path');

const target = process.argv[2];
if (!target) {
  console.log('  usage: inspect.js <workflow.yml>');
  process.exit(2);
}

// js-yaml may be installed beside this script or resolved from node_modules upwards.
let yaml;
try {
  yaml = require('js-yaml');
} catch (e) {
  console.log('  SKIP: js-yaml is not installed (npm i js-yaml in scripts/workflow-check)');
  process.exit(0);
}

let doc;
try {
  doc = yaml.load(fs.readFileSync(target, 'utf8'));
} catch (e) {
  console.log('  FAIL: ' + e.message);
  process.exit(1);
}

const problems = [];

// `on` parses to the boolean true in YAML 1.1 when unquoted, so accept both shapes.
const triggers = doc.on || doc['on'] || doc.true;
if (!triggers) problems.push('no trigger block');
if (!doc.jobs || typeof doc.jobs !== 'object') problems.push('no jobs block');

for (const [name, job] of Object.entries(doc.jobs || {})) {
  if (!job['runs-on']) problems.push(`job ${name} has no runs-on`);
  if (!Array.isArray(job.steps) || job.steps.length === 0) {
    problems.push(`job ${name} has no steps`);
    continue;
  }
  job.steps.forEach((s, i) => {
    if (!s.uses && !s.run) problems.push(`job ${name} step ${i + 1} has neither uses nor run`);
    if (s.uses && s.run) problems.push(`job ${name} step ${i + 1} has both uses and run`);
    // GitHub Actions does not permit the secrets context in `if:` expressions.
    if (s.if && /\bsecrets\./.test(s.if)) {
      problems.push(`job ${name} step ${i + 1} uses the secrets context in if:`);
    }
  });
}

if (problems.length) {
  problems.forEach((p) => console.log('  FAIL: ' + p));
  process.exit(1);
}

console.log(`  triggers : ${Object.keys(triggers).join(', ')}`);
// Report every job, not just the first. A workflow with several jobs is exactly where a check
// that stops at job one stops being a check.
for (const [name, job] of Object.entries(doc.jobs)) {
  const needs = job.needs ? `  (needs: ${[].concat(job.needs).join(', ')})` : '';
  console.log(`\n  job ${name}  runs-on=${job['runs-on']}${needs}`);
  job.steps.forEach((s, i) => {
    console.log(`   ${String(i + 1).padStart(2)}. ${s.name || s.uses}`);
  });
}
