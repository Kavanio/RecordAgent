import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { loadModule, startHarness, stopHarness } from '../../testing/harness.mjs';

let slashCommands;

before(async () => {
  await startHarness();
  slashCommands = await loadModule('/src/features/slash-command/slashCommands.ts');
});

after(async () => {
  await stopHarness();
});

test('解析命令名称和参数，并统一转为小写', () => {
  assert.deepEqual(slashCommands.parseSlashCommand(' /Rename  新会话名称 '), { name: 'rename', args: '新会话名称' });
  assert.deepEqual(slashCommands.parseSlashCommand('/'), { name: '', args: '' });
});

test('普通文本、转义命令和绝对路径不会被命令系统拦截', () => {
  assert.equal(slashCommands.parseSlashCommand('普通问题'), null);
  assert.equal(slashCommands.parseSlashCommand('//help'), null);
  assert.equal(slashCommands.parseSlashCommand('/Users/example/project'), null);
});

test('命令建议支持名称和别名，并在参数阶段关闭', () => {
  assert.deepEqual(slashCommands.suggestSlashCommands('/').map((command) => command.name), [
    'help', 'rename', 'status', 'tokens', 'today', 'agenda', 'spending', 'study-report', 'find-record', 'ask-record',
    'summarize-record', 'compare-records',
    'daily-review', 'weekly-review', 'todo-review', 'finance-review', 'study-review', 'study-plan',
  ]);
  assert.deepEqual(slashCommands.suggestSlashCommands('/u').map((command) => command.name), ['tokens']);
  assert.deepEqual(slashCommands.suggestSlashCommands('/rename 新名称'), []);
  assert.equal(slashCommands.findSlashCommand('usage')?.name, 'tokens');
});

test('今日汇总命令不接收参数', () => {
  assert.equal(slashCommands.findSlashCommand('today')?.requiresArgs, undefined);
  assert.deepEqual(slashCommands.suggestSlashCommands('/to').map((command) => command.name), ['tokens', 'today', 'todo-review']);
});

test('资料检索声明必填关键词，其余数据命令接受可选范围', () => {
  assert.equal(slashCommands.findSlashCommand('find-record')?.requiresArgs, true);
  assert.equal(slashCommands.findSlashCommand('agenda')?.requiresArgs, undefined);
  assert.equal(slashCommands.findSlashCommand('spending')?.requiresArgs, undefined);
  assert.equal(slashCommands.findSlashCommand('study-report')?.requiresArgs, undefined);
});

test('资料 AI 命令声明引用选择器用法和稳定的引用数量', () => {
  assert.equal(slashCommands.findSlashCommand('ask-record')?.usage, '/ask-record ? <问题>');
  assert.equal(slashCommands.findSlashCommand('summarize-record')?.usage, '/summarize-record ?');
  assert.equal(slashCommands.findSlashCommand('compare-records')?.usage, '/compare-records ? ?');
  assert.equal(slashCommands.asRecordReferenceCommand('ask-record'), 'ask-record');
  assert.equal(slashCommands.asRecordReferenceCommand('status'), null);
  assert.equal(slashCommands.parseRecordReferencePickerQuery('/ask-record ? 架构', 0), '架构');
  assert.equal(slashCommands.parseRecordReferencePickerQuery('/compare-records ? ?', 0), '');
  assert.equal(slashCommands.parseRecordReferencePickerQuery('/compare-records ?', 1), '');
  assert.equal(slashCommands.parseRecordReferencePickerQuery('/compare-records ?', 2), null);
});

test('注册阶段拒绝命令名或别名冲突', () => {
  assert.throws(() => slashCommands.validateSlashCommandRegistry([
    { name: 'help', aliases: ['h'], description: '', usage: '', execution: 'LOCAL' },
    { name: 'status', aliases: ['h'], description: '', usage: '', execution: 'LOCAL' },
  ]), /名称冲突/);
});

test('命令注册表区分本地、确定性查询和模型上下文命令', () => {
  assert.equal(slashCommands.findSlashCommand('status')?.execution, 'LOCAL');
  assert.equal(slashCommands.findSlashCommand('today')?.execution, 'QUERY');
  assert.equal(slashCommands.findSlashCommand('daily-review')?.execution, 'CONTEXT_PROMPT');
});

test('面板命令立即执行，Prompt 命令携带独立图标与颜色进入编辑态', () => {
  assert.equal(slashCommands.findSlashCommand('help')?.presentation.selection, 'IMMEDIATE');
  assert.equal(slashCommands.findSlashCommand('today')?.presentation.selection, 'IMMEDIATE');
  assert.equal(slashCommands.findSlashCommand('ask-record')?.presentation.selection, 'COMPOSE');
  assert.equal(slashCommands.findSlashCommand('study-review')?.presentation.icon, 'study');
  assert.match(slashCommands.findSlashCommand('study-review')?.presentation.color ?? '', /^#[0-9a-f]{6}$/i);
});
