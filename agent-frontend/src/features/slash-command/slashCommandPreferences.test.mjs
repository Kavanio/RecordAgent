import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { loadModule, resetModules, startHarness, stopHarness } from '../../testing/harness.mjs';

let slashCommandPreferences;
let slashCommands;

before(async () => {
  await startHarness();
});

after(async () => {
  await stopHarness();
});

beforeEach(async () => {
  // 命令偏好同样持有模块级缓存，逐用例重建模块图以隔离用例。
  localStorage.clear();
  resetModules();
  slashCommandPreferences = await loadModule('/src/features/slash-command/slashCommandPreferences.ts');
  slashCommands = await loadModule('/src/features/slash-command/slashCommands.ts');
});

test('默认情况下所有内置命令均启用且使用 Registry 信息', () => {
  const { SLASH_COMMANDS } = slashCommands;
  const states = slashCommandPreferences.applySlashCommandPreferences(SLASH_COMMANDS, {});

  assert.equal(states.length, SLASH_COMMANDS.length);
  assert.ok(states.every((state) => state.enabled));
  assert.equal(states[0].command.description, SLASH_COMMANDS[0].description);
});

test('偏好可以禁用命令并覆盖帮助信息，但不修改内置 Registry', () => {
  const { SLASH_COMMANDS } = slashCommands;
  const states = slashCommandPreferences.applySlashCommandPreferences(SLASH_COMMANDS, {
    help: {
      enabled: false,
      description: '自定义帮助说明',
      usage: '/help [name]',
    },
  });
  const help = states.find((state) => state.command.name === 'help');

  assert.equal(help?.enabled, false);
  assert.equal(help?.customized, true);
  assert.equal(help?.command.description, '自定义帮助说明');
  assert.equal(help?.command.usage, '/help [name]');
  assert.notEqual(SLASH_COMMANDS[0].description, '自定义帮助说明');
});

test('本地偏好即时影响运行时命令，并可单独恢复帮助信息', () => {
  const { SLASH_COMMANDS, findSlashCommand, getSlashCommandStates } = slashCommands;

  slashCommandPreferences.setSlashCommandEnabled('help', false);
  slashCommandPreferences.setSlashCommandDetails('help', { description: '本地帮助', usage: '/help local' });

  assert.equal(findSlashCommand('help'), undefined);
  assert.equal(getSlashCommandStates().find((state) => state.command.name === 'help')?.command.description, '本地帮助');

  slashCommandPreferences.resetSlashCommandDetails('help');
  const restored = getSlashCommandStates().find((state) => state.command.name === 'help');

  assert.equal(restored?.enabled, false);
  assert.equal(restored?.customized, false);
  assert.equal(restored?.command.description, SLASH_COMMANDS[0].description);
});
