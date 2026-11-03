import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { getReact, loadModule, mount, startHarness, stopHarness } from '../../testing/harness.mjs';

let Particles, handle;
const preferences = { enabled: true, style: 'emoji', emojis: ['🌸'], images: [] };
before(async () => {
  await startHarness();
  ({ WritingBurstParticles: Particles } = await loadModule('/src/components/common/WritingBurstParticles.tsx'));
});
after(stopHarness);
afterEach(async () => { await handle?.unmount(); handle = null; });

async function render(seed, next = preferences) {
  const element = getReact().createElement(Particles, { preferences: next, seed });
  if (!handle) handle = await mount(element);
  else await handle.render(element);
  return [...handle.query('[aria-hidden="true"]').children];
}
const value = (particle, name) => Number.parseFloat(particle.style.getPropertyValue(`--${name}`));
const motion = particles => particles.map(particle => particle.getAttribute('style'));

test('从字尾向右前方喷出，上中下三束渐次分开且同束远近不同', async () => {
  const particles = await render(17);
  assert.equal(particles.length, 6);
  const distances = particles.map(particle => Math.hypot(value(particle, 'end-x'), value(particle, 'end-y')));
  assert.ok(Math.max(...distances) - Math.min(...distances) > 8);
  assert.ok(new Set(particles.map(particle => value(particle, 'duration'))).size >= 4);
  assert.ok(new Set(particles.map(particle => value(particle, 'delay'))).size >= 4);
  for (let index = 0; index < particles.length; index++) {
    const particle = particles[index];
    const y = value(particle, 'end-y');
    if (index % 3 === 0) assert.ok(y < -16, '上束向右上方展开');
    if (index % 3 === 1) assert.ok(Math.abs(y) <= 2, '中束保持向前');
    if (index % 3 === 2) assert.ok(y > 16, '下束向右下方展开');
    const earlySlope = Math.abs(value(particle, 'kick-y') / value(particle, 'kick-x'));
    const finalSlope = Math.abs(y / value(particle, 'end-x'));
    assert.ok(earlySlope <= finalSlope, '先向前离开光标，再逐渐张开');
  }
});

test('多次输入均保持小范围且早于回收结束，重渲染与换图案不会重抽轨迹', async () => {
  for (let seed = 1; seed <= 32; seed++) {
    const particles = await render(seed);
    for (const particle of particles) {
      let lastX = 0;
      for (const phase of ['kick', 'float', 'end']) {
        const radius = Math.hypot(value(particle, `${phase}-x`), value(particle, `${phase}-y`));
        assert.ok(Number.isFinite(radius) && radius <= 112, `seed ${seed} 的 ${phase} 轨迹超出有界扇面`);
        const x = value(particle, `${phase}-x`);
        assert.ok(x > lastX, '粒子持续向右前进，不回到文字上方堆积');
        lastX = x;
      }
      assert.ok(value(particle, 'duration') + value(particle, 'delay') <= 438);
    }
  }
  const before = motion(await render(32));
  assert.deepEqual(motion(await render(32, { ...preferences, style: 'icons' })), before);
  assert.notDeepEqual(motion(await render(33)), before);
});
