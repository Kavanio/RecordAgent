import assert from 'node:assert/strict';
import test from 'node:test';
import { financeCategoryColor } from './financeCategoryColors.ts';

test('默认分类具有明确固定色；历史自定义分类去除首尾空格后保持一致', () => {
  assert.equal(financeCategoryColor('餐饮'), '#F59E0B');
  assert.equal(financeCategoryColor('交通'), '#3B82F6');
  assert.equal(financeCategoryColor(' 宠物 '), financeCategoryColor('宠物'));
  assert.match(financeCategoryColor('__proto__'), /^#[0-9A-F]{6}$/);
});

test('合法用户配色优先，无效配色和原型属性不能改变固定默认色', () => {
  assert.equal(financeCategoryColor('餐饮', 'expense', { 餐饮: '#12abcd' }), '#12ABCD');
  for (const color of ['red', '#abc', 'url(test)', '#12345678']) {
    assert.equal(financeCategoryColor('餐饮', 'expense', { 餐饮: color }), '#F59E0B');
  }
  assert.equal(financeCategoryColor('餐饮', 'expense', Object.create({ 餐饮: '#123456' })), '#F59E0B');
});
