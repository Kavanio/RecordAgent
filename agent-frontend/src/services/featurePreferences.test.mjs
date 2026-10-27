import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { loadModule, resetModules, startHarness, stopHarness } from '../testing/harness.mjs';

const STORAGE_KEY = 'butvan.featurePreferences';

let featurePreferences;

before(async () => {
  await startHarness();
});

after(async () => {
  await stopHarness();
});

beforeEach(async () => {
  // 偏好模块持有模块级缓存，逐用例重建模块图才能验证真实的首次读取行为。
  localStorage.clear();
  resetModules();
  featurePreferences = await loadModule('/src/services/featurePreferences.ts');
});

test('旧版偏好迁移时补齐聊天顶栏的安全默认值', () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ studyWindowMode: 'desktop' }));

  assert.deepEqual(featurePreferences.getFeaturePreferences(), {
    studyWindowMode: 'desktop',
    chatTopBar: { showDate: true, showTodos: true, showFinance: true, showHoliday: false, showWeather: false },
    calendar: { showStickyNotes: true, stickyNotesDefaultExpanded: false },
  });
});

test('单项顶栏开关不会覆盖其他功能偏好', () => {
  featurePreferences.setStudyWindowMode('in-app');
  const updated = featurePreferences.setChatTopBarPreference('showFinance', false);

  assert.equal(updated.studyWindowMode, 'in-app');
  assert.deepEqual(updated.chatTopBar, {
    showDate: true,
    showTodos: true,
    showFinance: false,
    showHoliday: false,
    showWeather: false,
  });
  assert.deepEqual(JSON.parse(localStorage.getItem(STORAGE_KEY)), updated);
});

test('损坏的本地配置回退到完整默认值', () => {
  localStorage.setItem(STORAGE_KEY, '{broken');

  assert.deepEqual(featurePreferences.getFeaturePreferences(), {
    studyWindowMode: 'page',
    chatTopBar: { showDate: true, showTodos: true, showFinance: true, showHoliday: false, showWeather: false },
    calendar: { showStickyNotes: true, stickyNotesDefaultExpanded: false },
  });
});
