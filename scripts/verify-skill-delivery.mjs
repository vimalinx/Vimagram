import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const ROOT = resolve(__dirname, '..');

function fail(message) {
  console.error(`❌ ${message}`);
  process.exit(1);
}

function pass(message) {
  console.log(`✅ ${message}`);
}

function parseFrontmatter(markdown) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  if (lines[0]?.trim() !== '---') {
    return {};
  }
  const out = {};
  for (let i = 1; i < lines.length; i += 1) {
    const line = lines[i]?.trim() ?? '';
    if (line === '---') break;
    if (!line || line.startsWith('#')) continue;
    const idx = line.indexOf(':');
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).trim();
    out[key] = value;
  }
  return out;
}

console.log('--- Verify offline skill delivery wiring ---');

const manifestPath = resolve(ROOT, 'plugin', 'openclaw.plugin.json');
if (!existsSync(manifestPath)) fail('plugin manifest missing: plugin/openclaw.plugin.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const skills = Array.isArray(manifest.skills) ? manifest.skills : [];
if (!skills.includes('skills')) {
  fail('plugin manifest must include "skills": ["skills"]');
}
pass('plugin manifest exposes skills directory');

const placeholderSkillPath = resolve(ROOT, 'plugin', 'skills', 'vimalinx-instance-profiles', 'SKILL.md');
if (!existsSync(placeholderSkillPath)) {
  fail('missing placeholder skill: plugin/skills/vimalinx-instance-profiles/SKILL.md');
}
pass('placeholder skill bundle exists');

const debugSkillPath = resolve(ROOT, 'plugin', 'skills', 'vimalinx-profile', 'SKILL.md');
if (!existsSync(debugSkillPath)) {
  fail('missing debug skill: plugin/skills/vimalinx-profile/SKILL.md');
}
const debugSkill = readFileSync(debugSkillPath, 'utf8');
const fm = parseFrontmatter(debugSkill);
if (fm.name !== 'vimalinx-profile') fail('debug skill name frontmatter mismatch');
if (fm['command-dispatch'] !== 'tool') fail('debug skill must set command-dispatch: tool');
if (fm['command-tool'] !== 'vimalinx_profile') fail('debug skill must set command-tool: vimalinx_profile');
pass('debug skill frontmatter wiring is correct');

const pluginIndexPath = resolve(ROOT, 'plugin', 'index.ts');
const pluginIndex = readFileSync(pluginIndexPath, 'utf8');
if (!pluginIndex.includes('createVimalinxProfileTool')) {
  fail('plugin index must import createVimalinxProfileTool');
}
if (!pluginIndex.includes('api.registerTool((ctx) => createVimalinxProfileTool(ctx));')) {
  fail('plugin index must register vimalinx profile tool');
}
pass('plugin registers vimalinx profile tool');

const toolPath = resolve(ROOT, 'plugin', 'src', 'tools', 'vimalinx-profile-tool.ts');
const toolSource = readFileSync(toolPath, 'utf8');
if (!toolSource.includes('name: "vimalinx_profile"')) {
  fail('tool name must be vimalinx_profile');
}
if (!toolSource.includes('ownerOnly: true')) {
  fail('vimalinx_profile tool should be ownerOnly for safety');
}
pass('tool implementation contains expected name and ownerOnly guard');

console.log('--- verify-skill-delivery: OK ---');
