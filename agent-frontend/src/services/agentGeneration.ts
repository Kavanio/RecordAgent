/** 后端确认的个人状态代次；每个请求发出时复制进请求头，旧请求保持原代次。 */
let generation: number | null = null;
export function setAgentGeneration(next: number): void {
  generation = generation === null ? next : Math.max(generation, next);
}
export function withAgentGeneration(headers: Headers): Headers {
  if (generation !== null) headers.set('X-Agent-Generation', String(generation));
  return headers;
}
