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

test('轨迹远近、角度和节奏各不相同，形成小簇喷散而非等距圆环', async () => {
  const particles = await render(17);
  assert.equal(particles.length, 8);
  const distances = particles.map(particle => Math.hypot(value(particle, 'end-x'), value(particle, 'end-y')));
  assert.ok(Math.max(...distances) - Math.min(...distances) > 8);
  assert.ok(new Set(particles.map(particle => value(particle, 'duration'))).size >= 4);
  assert.ok(new Set(particles.map(particle => value(particle, 'delay'))).size >= 4);
  const angles = particles.map(particle => Math.atan2(value(particle, 'kick-y'), value(particle, 'kick-x'))).sort((a, b) => a - b);
  const gaps = angles.map((angle, i) => (angles[(i + 1) % angles.length] - angle + Math.PI * 2) % (Math.PI * 2));
  assert.ok(Math.max(...gaps) - Math.min(...gaps) > .4);
  assert.equal(particles.filter(particle => value(particle, 'kick-y') < 0).length, 6);
});

test('多次输入均保持小范围且早于回收结束，重渲染与换图案不会重抽轨迹', async () => {
  for (let seed = 1; seed <= 32; seed++) {
    const particles = await render(seed);
    for (const particle of particles) {
      for (const phase of ['kick', 'float', 'end']) {
        const radius = Math.hypot(value(particle, `${phase}-x`), value(particle, `${phase}-y`));
        assert.ok(Number.isFinite(radius) && radius <= 34.02, `seed ${seed} 的 ${phase} 轨迹超出小范围`);
      }
      assert.ok(value(particle, 'duration') + value(particle, 'delay') <= 534);
    }
  }
  const before = motion(await render(32));
  assert.deepEqual(motion(await render(32, { ...preferences, style: 'icons' })), before);
  assert.notDeepEqual(motion(await render(33)), before);
});
